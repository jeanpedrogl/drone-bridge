# Manual do desenvolvedor — programas de computador para o Drone Bridge

Como escrever **um programa no computador que pilota um DJI Mini SE** pelo app *Drone Bridge*:
teclado, gamepad, visão computacional, script de voo. Este manual mostra, **de forma rápida, por
qual tópico cada coisa viaja** e como usar cada comando. A tabela completa de tópicos, argumentos,
resultados e erros está no [`PROTOCOLO.md`](PROTOCOLO.md).

Os exemplos usam os comandos de terminal do ROS2 (`ros2 topic pub`/`echo`), que funcionam em qualquer
máquina com o ROS carregado; a seção 5 traz um programa Python (`rclpy`) completo de referência.

> **Segurança primeiro.** Drones ferem pessoas e causam danos. Primeiros testes **sem hélices**;
> depois área aberta e **baixo**, com o controle físico na mão e o botão de pânico do celular ao
> alcance (seções 4 e 9). Você responde pelo voo e pela legislação do seu país. Sem garantia
> ([`LICENSE`](LICENSE)).

> **Estado.** Com um Mini SE real já funcionaram: stick mode, decolar, pousar, gimbal e giro.
> **Não confirmados em voo:** o sentido do giro (seção 11), `flight.cancel_land_or_rth`, os comandos
> de configuração e o vídeo em todos os presets. Trate o resto como hipótese até verificar.

Sumário: [1 Visão geral](#1-visão-geral) · [2 Ambiente](#2-preparar-o-ambiente) ·
[3 Tópicos](#3-os-tópicos-um-a-um) · [4 Segurança](#4-regras-de-segurança-obrigatórias) ·
[5 Programa de referência](#5-programa-de-referência-em-python) · [6 Comandos RPC](#6-comandos-rpc-um-a-um) ·
[7 Outras entradas](#7-adaptando-a-outras-entradas) · [8 Testar](#8-testar-com-segurança) ·
[9 Primeiro voo](#9-ordem-segura-para-o-primeiro-voo) · [10 Problemas](#10-solução-de-problemas) ·
[11 Limitações](#11-limitações-e-o-que-não-foi-verificado) · [12 Estender o app](#12-estender-o-app-android) ·
[A Sem ROS](#apêndice-a--cliente-sem-ros-websocket-direto)

---

## 1. Visão geral

```
 seu programa ──ROS2──▶ rosbridge ──WebSocket──▶ app Drone Bridge ──DJI SDK──▶ controle ──▶ drone
 (Python...)    tópicos   (no PC)    Wi-Fi         (celular)          USB      remoto     rádio
      ▲                                                │
      └──────── telemetria, estado, vídeo, respostas ──┘
```

- Seu programa decide *o que* fazer e publica **tópicos ROS2**; o `rosbridge_websocket` (porta
  9090) os traduz em JSON por WebSocket. **O celular é o cliente**: conecta ao computador, não o contrário.
- O app não tem lógica de controle: repassa ao DJI SDK, repete o último comando a cada 50 ms, zera
  tudo se o computador calar (500 ms) e devolve telemetria, estado e vídeo.
- **O pânico existe só no celular.** Nenhum programa do computador o aciona nem o desativa.

## 2. Preparar o ambiente

Ubuntu 22.04, ROS2 Humble e `ros-humble-rosbridge-suite`.

1. **Rede**: computador e celular na mesma rede. Usual: o computador cria um hotspot (GNOME →
   Wi-Fi → "Criar rede Wi-Fi…"), que costuma dar `10.42.0.1` ao computador (`ip addr`).
2. **Rosbridge**: `ros2 launch rosbridge_server rosbridge_websocket_launch.xml`
3. **Celular**: toque na linha de status do app e informe `IP:porta` (padrão `10.42.0.1:9090`; sem
   porta, o app usa a 9090).
4. **Confira**: `ros2 topic echo /phone/telemetria` deve mostrar ~2 mensagens por segundo.

Com venv, crie-o com `python3 -m venv --system-site-packages .venv` (senão não enxerga o `rclpy`)
e compile com `python3 -m colcon build`, nunca `colcon build` puro.

### 2.1 A rede não tem senha nem criptografia

Quem alcançar a porta 9090 pode publicar em `/drone/cmd_vel` e decolar o drone. Use uma rede só
sua (hotspot com senha). Prender o rosbridge à interface e aos tópicos do app **reduz** a exposição,
mas não substitui a rede privada (sintaxe de `topics_glob` do rosbridge 2.0.8 do Humble):

```bash
ros2 run rosbridge_server rosbridge_websocket --ros-args \
  -p port:=9090 -p address:=10.42.0.1 -p topics_glob:="'[/drone/*,/phone/*]'"
```

## 3. Os tópicos, um a um

Convenção de eixos: **REP 103** — `x` frente, `y` **esquerda**, `z` cima, `angular.z` anti-horário
positivo. O app converte para o DJI; não "corrija" sinais. Tudo em **[-1, 1]**, onde 1,0 vale:

| Eixo | 1,0 equivale a |
|---|---|
| `linear.x`, `linear.y` | 4 m/s |
| `linear.z` | 2 m/s |
| `angular.z` | 85 °/s |
| gimbal | 50 °/s |

(Limites no topo de `DroneController.kt`; um teste confere que estes números batem.) Nos primeiros
testes, use 0,2 a 0,3. Sem mensagem de velocidade ou gimbal por **500 ms**, o app zera.

### `/drone/stick_mode` — `std_msgs/Bool`, PC → celular

`true` liga o controle por software; `false` devolve ao controle físico. Publique **só quando o
estado muda**. O app recusa armar com o app em segundo plano ou o drone desconectado; a telemetria
(`stick_mode`) mostra o estado real.

```bash
ros2 topic pub --once /drone/stick_mode std_msgs/msg/Bool "{data: true}"    # arma
ros2 topic pub --once /drone/stick_mode std_msgs/msg/Bool "{data: false}"   # devolve ao controle físico
```

### `/drone/cmd_vel` — `geometry_msgs/Twist`, PC → celular

Velocidade contínua; **só vale com o stick mode ligado**. Mande a ~20 Hz, mesmo em zeros; o app
corta valores fora de [-1, 1] e transforma `NaN` em 0.

```bash
ros2 topic pub -r 20 /drone/cmd_vel geometry_msgs/msg/Twist "{linear: {x: 0.3}}"     # frente a 30% (1,2 m/s)
ros2 topic pub -r 20 /drone/cmd_vel geometry_msgs/msg/Twist "{linear: {y: 0.3}}"     # para a ESQUERDA
ros2 topic pub -r 20 /drone/cmd_vel geometry_msgs/msg/Twist "{angular: {z: 0.3}}"    # gira anti-horário
# Ctrl+C: as mensagens param e o app zera em 0,5 s (o drone paira, ainda em stick mode)
```

### `/drone/gimbal_pitch_rate` — `std_msgs/Float32`, PC → celular

Inclinação da câmera, −1..1 (positivo = sobe), a 20 Hz enquanto quiser movimento. **Funciona sem
stick mode.** Ao parar, o gimbal mantém o ângulo.

```bash
ros2 topic pub -r 20 /drone/gimbal_pitch_rate std_msgs/msg/Float32 "{data: 0.5}"   # sobe; Ctrl+C para
```

### `/drone/takeoff`, `/drone/land` — `std_msgs/Empty`, PC → celular

Atalhos **sem resposta**: o resultado só aparece na tela do celular. Se quiser saber se deu certo,
use `flight.take_off`/`flight.land` (seção 6.4).

```bash
ros2 topic pub --once /drone/takeoff std_msgs/msg/Empty "{}"
ros2 topic pub --once /drone/land std_msgs/msg/Empty "{}"
```

### `/drone/rpc/request` e `/phone/rpc/response` — `std_msgs/String` (JSON)

Cada comando da seção 6 vai como um JSON dentro do `data` do `request`; a resposta volta no
`response` com o **mesmo `id`**. O `response` é de **todos** os programas ligados: use ids únicos
por programa (por exemplo `"<prefixo aleatório>-<contador>"`) e ignore respostas de ids que você não enviou.

```bash
ros2 topic echo /phone/rpc/response                     # num terminal: as respostas
ros2 topic pub --once /drone/rpc/request std_msgs/msg/String "{data: '{\"id\": \"t1\", \"cmd\": \"system.ping\"}'}"
# resposta: data: '{"id": "t1", "cmd": "system.ping", "ok": true, "result": "pong"}'
```

### `/phone/telemetria` — `std_msgs/String` (JSON), celular → PC

Resumo a 2 Hz, sem pedir nada: `drone_conectado`, `stick_mode`, `bateria_percent`, `altitude_m`,
`velocidade_ms`, `satelites`, `voando` (`null` quando ainda não há dado).

```bash
ros2 topic echo /phone/telemetria
# data: '{"drone_conectado": true, "stick_mode": false, "bateria_percent": 80, "altitude_m": 0.0, ...}'
```

### `/phone/state` — `std_msgs/String` (JSON), celular → PC

Estado detalhado (voo, gimbal, câmera, bateria, controle). **Só chega se você pedir** com
`state.stream_start` (seção 6.2).

```bash
ros2 topic echo /phone/state
```

### `/phone/drone_camera/compressed` — `sensor_msgs/CompressedImage`, celular → PC

Vídeo do drone em JPEG (`data` são os bytes, já decodificados pelo rosbridge). É uma cópia
reencodada, não o fluxo original (seção 6.3). Ao assinar, use fila de 1 e guarde só o quadro mais recente.

```bash
ros2 topic hz /phone/drone_camera/compressed            # quadros por segundo que chegam de fato
ros2 run rqt_image_view rqt_image_view                  # para ver; precisa de ros-humble-compressed-image-transport
```

## 4. Regras de segurança obrigatórias

Todo programa que pilota o drone deve cumprir estas regras (o programa da seção 5 segue todas).

1. **Comece desarmado.** Nada de velocidade sai até o piloto armar de propósito (tecla, botão).
2. **`stick_mode` só quando o estado muda.** Cada `true` refaz a ativação do virtual stick. E só depois
   que o celular estiver ouvindo (telemetria chegando): uma mensagem publicada antes de o DDS descobrir o
   assinante se perde sem aviso.
3. **20 Hz enquanto armado**, mesmo em zeros: calar é como o app sabe que você travou. Não use o
   silêncio para "parar" um movimento planejado.
4. **Desarmar zera antes de soltar o controle**: publique um `Twist` zerado e depois
   `stick_mode: false`, em `Ctrl+C`, exceção e ao fechar (`try/finally`). **No Humble, inicie com
   `rclpy.init(signal_handler_options=SignalHandlerOptions.NO)`**: com o `rclpy.init()` padrão, o
   Ctrl+C invalida o contexto ROS antes do `finally`, a publicação falha ("publisher's context is
   invalid") e o drone fica pairando em stick mode sem o celular saber que você saiu.
5. **Sincronize com o celular.** Se a telemetria disser `stick_mode: false` enquanto você está
   armado (piloto tocou ASSUMIR CONTROLE, app em segundo plano, drone desconectado, **pânico**),
   desarme também e **não rearme sozinho**: o app só recusa rearmar em segundo plano. Tolere ~3 s
   logo após armar, enquanto o celular liga o modo.
6. **Suavize entradas liga/desliga** (rampa de ~0,3 s subindo, ~0,15 s descendo); entradas
   ruidosas (visão, mouse) pedem zona morta e filtro.
7. **Valide e limite**: nunca mande fora de [-1, 1], trate `NaN`; se a fonte some (webcam, gamepad),
   pare — não repita o último valor. O app também corta, mas é a segunda barreira.
8. **A rede não é a segurança.** Depois do watchdog o drone **fica pairando em stick mode**; o
   controle físico só volta com **ASSUMIR CONTROLE (RC)** ou pânico no celular. Prefira movimentos
   curtos que terminam sozinhos.
9. **Comandos perigosos pedem confirmação** (`"confirm": true`, ex. `flight.set_failsafe`); peça
   ao usuário, não ponha como padrão automático.
10. **Teste em domínio ROS isolado** (seção 8): um teste no domínio padrão pode atingir o drone real.

## 5. Programa de referência em Python

Um nó `rclpy` completo que segue as regras: arma uma vez, quando o celular estiver ouvindo, sobe 2 s a 30% e paira; desarma se o
celular retomar o controle; manda RPC **sem bloquear** (com id único e resposta em callback); e
desarma de verdade em Ctrl+C e `kill`. Num programa real, a decisão de armar e as velocidades vêm da
sua entrada (teclado, visão...), e a lógica que transforma entrada em números deve ficar em funções
puras, testáveis sem ROS. Leia a seção 9 antes de rodá-lo com o drone.

```python
import json
import signal
import time
import uuid

import rclpy
from geometry_msgs.msg import Twist, Vector3
from rclpy.node import Node
from rclpy.signals import SignalHandlerOptions
from std_msgs.msg import Bool, String


class Piloto(Node):
    def __init__(self):
        super().__init__("piloto")
        self.vel = self.create_publisher(Twist, "/drone/cmd_vel", 10)
        self.stick = self.create_publisher(Bool, "/drone/stick_mode", 10)
        self.rpc = self.create_publisher(String, "/drone/rpc/request", 10)
        self.create_subscription(String, "/phone/telemetria", self.ao_telemetria, 10)
        self.create_subscription(String, "/phone/rpc/response", self.ao_resposta, 10)
        self.telemetria, self.armado, self.armado_em = {}, False, None
        self.prefixo, self.contador, self.pendentes = uuid.uuid4().hex[:8], 0, {}
        self.create_timer(0.05, self.passo)                       # 20 Hz

    def pedir(self, cmd, args=None, ao_responder=print):
        """Envia um comando RPC sem bloquear; a resposta chega em ao_responder."""
        self.contador += 1
        id_ = f"{self.prefixo}-{self.contador}"                   # único por programa
        self.pendentes[id_] = ao_responder
        self.rpc.publish(String(data=json.dumps({"id": id_, "cmd": cmd, "args": args or {}})))

    def ao_resposta(self, msg):
        resposta = json.loads(msg.data)
        responder = self.pendentes.pop(resposta.get("id"), None)  # ignora respostas de outros
        if responder:
            responder(resposta)

    def ao_telemetria(self, msg):
        self.telemetria = json.loads(msg.data)

    def passo(self):
        celular_ouvindo = self.telemetria and self.stick.get_subscription_count() > 0
        if self.armado_em is None and celular_ouvindo:   # no seu programa: só quando o piloto pedir (regra 1)
            self.armar()                                  # (antes da descoberta do DDS, o `true` se perderia)
        if not self.armado:
            return
        if time.monotonic() - self.armado_em > 3.0 and self.telemetria.get("stick_mode") is False:
            self.desarmar()                         # regra 5: o celular retomou o controle; não rearme
            return
        subir = 0.3 if time.monotonic() - self.armado_em < 2.0 else 0.0   # sobe 2 s a 30% (0,6 m/s), depois paira
        self.vel.publish(Twist(linear=Vector3(z=subir)))   # a 20 Hz, mesmo zerado (regra 3)

    def armar(self):
        self.armado, self.armado_em = True, time.monotonic()
        self.stick.publish(Bool(data=True))         # só na mudança de estado (regra 2)

    def desarmar(self):
        if self.armado:
            self.vel.publish(Twist())                             # zera antes de soltar
            self.stick.publish(Bool(data=False))
        self.armado = False


def sigterm(*_):
    raise KeyboardInterrupt                                       # kill também passa pelo finally


def main():
    rclpy.init(signal_handler_options=SignalHandlerOptions.NO)   # Ctrl+C sem matar o contexto
    signal.signal(signal.SIGTERM, sigterm)
    no = Piloto()
    no.pedir("system.ping")
    try:
        rclpy.spin(no)
    except KeyboardInterrupt:
        pass
    finally:
        no.desarmar()                                             # Ctrl+C, erro ou fim
        no.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
```

**Não espere a resposta de um RPC dentro de um callback** (por exemplo girando `rclpy.spin_once`
num laço até ela chegar): o nó já está em `rclpy.spin`, e isso trava ou dá erro. Use o padrão acima,
`pedir(...)` com a resposta num callback. Para ler o vídeo, assine
`/phone/drone_camera/compressed` com fila 1 e decodifique `bytes(msg.data)` com
`cv2.imdecode` fora do callback.

## 6. Comandos RPC, um a um

Todos usam os dois tópicos da seção 3. Para testar à mão, defina esta função no terminal e deixe
`ros2 topic echo /phone/rpc/response` aberto em outro:

```bash
rpc() { ros2 topic pub --once /drone/rpc/request std_msgs/msg/String "{data: '$1'}" > /dev/null; }
rpc '{"id": "t1", "cmd": "system.ping"}'
```

- **`ok: true` quer dizer "o drone aceitou", não "terminou"**: decolar, pousar e voltar para casa
  levam segundos; confirme pela telemetria ou pelo estado.
- Escritas respondem `"result": null`; comandos marcados **confirmar** exigem `"confirm": true` em `args`.
- Sempre há uma resposta; no máximo `Sem resposta do drone em 10s.`. `system.commands` lista tudo, direto do app.

### 6.1 Sistema

| Comando | Para que serve | Resultado |
|---|---|---|
| `system.ping` | Testa a ligação ponta a ponta (não o drone). | `"pong"` |
| `system.commands` | Lista os comandos do app. | `[{cmd, description, args, requires_confirm, probe}]` |
| `system.info` | Modelo, firmware e componentes presentes. | `{connected, model, firmware, has_*}` |
| `system.probe` | Roda todas as leituras e diz quais **este drone** responde (até 8 s). | `{comando: {ok, result \| error}}` |

```bash
rpc '{"id": "s1", "cmd": "system.ping"}'
rpc '{"id": "s2", "cmd": "system.commands"}'
rpc '{"id": "s3", "cmd": "system.info"}'
rpc '{"id": "s4", "cmd": "system.probe"}'
```

### 6.2 Estado

| Comando | Para que serve |
|---|---|
| `state.get` | Uma foto do estado. `sections` (opcional): `product`, `flight`, `gimbal`, `camera`, `battery`, `remote`. |
| `state.stream_start` | Publica o estado em `/phone/state` a cada `interval_ms` (mín. 100), com `sections` opcional. |
| `state.stream_stop` | Para a publicação (também para sozinha se o PC desconectar). |

Nada é enviado sozinho. `velocity_ms` está em NED (z para **baixo**), não no padrão do `cmd_vel`.

```bash
rpc '{"id": "e1", "cmd": "state.get", "args": {"sections": ["flight", "battery"]}}'
rpc '{"id": "e2", "cmd": "state.stream_start", "args": {"interval_ms": 500, "sections": ["flight"]}}'
rpc '{"id": "e3", "cmd": "state.stream_stop"}'
```

### 6.3 Vídeo enviado ao computador

| Comando | Para que serve |
|---|---|
| `video.get` | Lê `{fps, max_width, jpeg_quality}`. |
| `video.set_quality` | Muda, em voo, `fps` (1–30), `max_width` (160–1920), `jpeg_quality` (10–95); omitido = mantém; fora da faixa = erro, nada muda. |

Padrão: 8 fps, 640 px, qualidade 50. Mais fps/pixels/qualidade = mais tráfego no Wi-Fi e mais CPU no
celular; se o Wi-Fi não acompanha, o app descarta quadros. Não afeta o link do drone com o controle.

```bash
rpc '{"id": "v1", "cmd": "video.get"}'
rpc '{"id": "v2", "cmd": "video.set_quality", "args": {"fps": 12, "max_width": 480, "jpeg_quality": 35}}'
```

### 6.4 Voo

| Comando | Para que serve |
|---|---|
| `flight.take_off` | Decola (não exige stick mode). |
| `flight.land` | Pousa; se o drone espera confirmação de pouso, confirma. |
| `flight.cancel_takeoff` | Cancela uma decolagem em andamento (erro se não houver). |
| `flight.cancel_landing` | Cancela um pouso em andamento (erro se não houver). |
| `flight.cancel_land_or_rth` | Cancela um pouso **ou** um retorno, o que estiver acontecendo — nunca a decolagem; erro `Nada para cancelar` se não houver. É a tecla "cancelar" certa. |
| `flight.settings` | Lê os ajustes de voo (até 6 s); cada item `{ok, value \| error}`. |
| `flight.set_max_height` | Altura máxima (`meters`). |
| `flight.set_max_radius` | Raio máximo a partir de casa (`meters`). |
| `flight.set_radius_limit` | Liga/desliga o limite de raio (`enabled`). |
| `flight.set_low_battery_threshold` | Aviso de bateria baixa (`percent`). |
| `flight.set_serious_low_battery_threshold` | Bateria crítica, quando o drone age sozinho (`percent`). |
| `flight.set_failsafe` | **confirmar** — comportamento ao perder o sinal: `HOVER`, `LANDING` ou `GO_HOME`. |

As faixas dos `flight.set_*` são as do SDK: o app não as valida, o drone responde com erro. Em
programas com teclado, faça decolar/pousar exigirem **segurar a tecla ~1 s**.

```bash
rpc '{"id": "f1", "cmd": "flight.take_off"}'
rpc '{"id": "f2", "cmd": "flight.cancel_takeoff"}'
rpc '{"id": "f3", "cmd": "flight.land"}'
rpc '{"id": "f4", "cmd": "flight.cancel_landing"}'
rpc '{"id": "f5", "cmd": "flight.cancel_land_or_rth"}'
rpc '{"id": "f6", "cmd": "flight.settings"}'
rpc '{"id": "f7", "cmd": "flight.set_max_height", "args": {"meters": 30}}'
rpc '{"id": "f8", "cmd": "flight.set_max_radius", "args": {"meters": 100}}'
rpc '{"id": "f9", "cmd": "flight.set_radius_limit", "args": {"enabled": true}}'
rpc '{"id": "f10", "cmd": "flight.set_low_battery_threshold", "args": {"percent": 30}}'
rpc '{"id": "f11", "cmd": "flight.set_serious_low_battery_threshold", "args": {"percent": 15}}'
rpc '{"id": "f12", "cmd": "flight.set_failsafe", "args": {"behavior": "GO_HOME", "confirm": true}}'
```

### 6.5 Retorno para casa

| Comando | Para que serve |
|---|---|
| `home.go_home` | Inicia o retorno (no comportamento padrão da DJI: sobe à altura de retorno, volta e pousa). |
| `home.cancel_go_home` | Cancela o retorno (o drone fica pairando). |
| `home.get` | Lê a casa: `{latitude, longitude, valid}`. |
| `home.set_here` | Casa = posição atual do drone. |
| `home.set_location` | Casa em coordenadas (`latitude`, `longitude`). |
| `home.set_return_height` | Altura do retorno (`meters`). |
| `home.set_smart_rth` | Retorno inteligente por bateria (`enabled`). |

A casa é registrada pelo drone na decolagem, com GPS; sem casa válida (`valid: false` em `home.get`),
espere que o SDK recuse o retorno. Nada disto foi testado em voo ainda.

```bash
rpc '{"id": "h1", "cmd": "home.get"}'
rpc '{"id": "h2", "cmd": "home.set_here"}'
rpc '{"id": "h3", "cmd": "home.set_location", "args": {"latitude": -23.5505, "longitude": -46.6333}}'
rpc '{"id": "h4", "cmd": "home.set_return_height", "args": {"meters": 40}}'
rpc '{"id": "h5", "cmd": "home.set_smart_rth", "args": {"enabled": true}}'
rpc '{"id": "h6", "cmd": "home.go_home"}'
rpc '{"id": "h7", "cmd": "home.cancel_go_home"}'
```

### 6.6 Gimbal

| Comando | Para que serve |
|---|---|
| `gimbal.rotate` | Gira: `mode` = `SPEED` (°/s), `RELATIVE_ANGLE` ou `ABSOLUTE_ANGLE` (graus); `pitch`, `roll`, `yaw` opcionais; `time` (s) só nos modos de ângulo. |
| `gimbal.reset` | Volta à posição inicial. |
| `gimbal.set_mode` | `FREE`, `FPV` ou `YAW_FOLLOW`. |
| `gimbal.set_pitch_range_extension` | Estende a inclinação para cima (Mini SE: de 0° para +20°). |
| `gimbal.get_pitch_range_extension` | Lê se a extensão está ligada (`true`/`false`). |
| `gimbal.capabilities` | O que este gimbal aceita: `{"ADJUST_PITCH": true, ...}`. |

No **Mini SE só a inclinação (pitch) é controlável** (−90° a 0°, +20° com a extensão); yaw e roll não
fazem efeito, mas o SDK não recusa. Para movimento contínuo prefira o tópico
`/drone/gimbal_pitch_rate`. **`gimbal.rotate` em `SPEED` não tem watchdog**: mande `pitch: 0` para parar.

```bash
rpc '{"id": "g1", "cmd": "gimbal.capabilities"}'
rpc '{"id": "g2", "cmd": "gimbal.set_pitch_range_extension", "args": {"enabled": true}}'
rpc '{"id": "g3", "cmd": "gimbal.get_pitch_range_extension"}'
rpc '{"id": "g4", "cmd": "gimbal.rotate", "args": {"mode": "ABSOLUTE_ANGLE", "pitch": -45, "time": 1.0}}'
rpc '{"id": "g5", "cmd": "gimbal.rotate", "args": {"mode": "SPEED", "pitch": 0}}'
rpc '{"id": "g6", "cmd": "gimbal.set_mode", "args": {"mode": "FREE"}}'
rpc '{"id": "g7", "cmd": "gimbal.reset"}'
```

### 6.7 Câmera

| Comando | Para que serve |
|---|---|
| `camera.set_mode` | `SHOOT_PHOTO` ou `RECORD_VIDEO`. |
| `camera.get_mode` | Lê o modo atual (`SHOOT_PHOTO`, `RECORD_VIDEO` ou outro do SDK, como `PLAYBACK`). |
| `camera.start_photo` | Tira uma foto (exige `SHOOT_PHOTO`). |
| `camera.stop_photo` | Interrompe a foto — só importa nos modos de intervalo. |
| `camera.start_record` | Começa a gravar (exige `RECORD_VIDEO`). |
| `camera.stop_record` | Para a gravação. |

Foto e vídeo vão para o cartão SD do drone (o app não baixa mídia). O `ok` é "aceito": confirme em
`state.get` (seção `camera`: `is_recording`, `storage`).

```bash
rpc '{"id": "c1", "cmd": "camera.get_mode"}'
rpc '{"id": "c2", "cmd": "camera.start_photo"}'
rpc '{"id": "c3", "cmd": "camera.stop_photo"}'
rpc '{"id": "c4", "cmd": "camera.set_mode", "args": {"mode": "RECORD_VIDEO"}}'
rpc '{"id": "c5", "cmd": "camera.start_record"}'
rpc '{"id": "c6", "cmd": "camera.stop_record"}'
```

## 7. Adaptando a outras entradas

Toda entrada acaba nos mesmos números `(x, y, z, yaw, gimbal)` em [-1, 1]; o que muda é o tratamento:

| Entrada | Como mapear | Cuidados |
|---|---|---|
| **Teclado** | WASD = x/y; Espaço/Shift = z. | Rampa; normalizar diagonais; soltar tudo ao perder o foco; eventos de tecla apertada/solta, não `cv2.waitKey`. |
| **Mouse** | Arrastar como joystick (yaw, gimbal). | Zona morta, curva exponencial, filtro; soltar o botão zera. |
| **Gamepad** | Sticks já dão [-1, 1] (`pygame.joystick`). | Zona morta; botão de armar explícito; gamepad desconectado = parar. |
| **Visão / gestos** | Gesto classificado → velocidade fixa num eixo. | Estabilize a classificação; "sem detecção" = pairar. |
| **Script / missão** | Sequência temporizada, como o programa da seção 5. | Laço de 20 Hz; `try/finally`; defina `flight.set_max_height`/`flight.set_max_radius` antes. |

Dois programas pilotando disputam o `cmd_vel` (vale a última mensagem): combine fontes **dentro de um
único nó** que publica uma só vez.

## 8. Testar com segurança

**Domínio ROS isolado.** Se o rosbridge e o celular de verdade estiverem no ar, qualquer teste no
domínio padrão envia comandos reais. Para ver o que o seu programa publica sem risco, rode-o num
domínio isolado e observe os tópicos:

```bash
export ROS_DOMAIN_ID=87 ROS_LOCALHOST_ONLY=1
ros2 node list                          # deve estar vazio antes de começar
python3 meu_programa.py &
ros2 topic echo /drone/cmd_vel          # confira valores, sinais e o ritmo (ros2 topic hz /drone/cmd_vel)
```

**Lógica pura com `pytest`.** Rampas, zonas mortas e a conversão de entrada em números devem ser
funções sem ROS e sem janela, testadas em milissegundos. **Do lado Android**, `./gradlew
:app:testDebugUnitTest` cobre o despachante RPC, os limites e a cobertura desta documentação.

## 9. Ordem segura para o primeiro voo

1. **Sem hélices, stick mode desligado.** Conecte ao computador e ao drone; rode `system.probe` e
   `gimbal.capabilities`; confira telemetria e vídeo.
2. **Sem hélices, armado.** Arme, mande velocidades pequenas; confira o stick mode no celular e os
   valores em `ros2 topic echo /drone/cmd_vel`; pare de mandar e veja o app zerar. Teste o gimbal, o
   botão **ASSUMIR CONTROLE (RC)** e o **Ctrl+C** do seu programa (o celular deve mostrar o stick mode desligado).
3. **Com proteção de hélices**, área aberta, **baixo** (1–2 m), controle físico na mão e o dedo perto
   do pânico. Confira a **direção de cada eixo e do giro** (o sinal do yaw é suposição, seção 11).
4. Só então aumente altura, velocidade e distância. Respeite a regulamentação (no Brasil, ANAC/DECEA)
   e mantenha o drone em linha de visão.

## 10. Solução de problemas

| Sintoma | Causa provável e o que fazer |
|---|---|
| Sem telemetria | Rosbridge fora do ar (`ss -tln \| grep 9090`); IP/porta errados no app; redes diferentes; firewall na 9090. |
| `/phone/...` não aparece | O celular ainda não conectou: os tópicos só existem depois. |
| "Comando desconhecido" | APK desatualizado: reinstale com **Run**. |
| "Sem resposta do drone em 10s" | Componente indisponível (drone desconectado do controle) ou comando sem suporte. |
| Programa trava esperando um RPC | Esperou a resposta dentro de um callback: use resposta em callback (seção 5). |
| Respostas trocadas entre programas | Ids repetidos: use ids únicos por programa. |
| Ctrl+C e o celular continua em stick mode | `rclpy.init()` padrão: use `SignalHandlerOptions.NO` (regra 4). |
| Armado, mas não se move | Telemetria com `stick_mode: false` (veja a mensagem na tela; o app **recusa armar em segundo plano**), drone desconectado, ou ele não está no ar. |
| Drone parado em stick mode após o programa cair | Esperado (watchdog = pairar): toque **ASSUMIR CONTROLE (RC)** ou **PÂNICO**. |
| App fecha ao abrir após instalar | Instalado com **Apply Changes**: use **Run**. |
| "Falha ao registrar" | App Key da DJI ausente/de outro identificador; sem internet no primeiro uso. |
| `ModuleNotFoundError` ao rodar o nó | `colcon build` em vez de `python3 -m colcon build`. |
| Imagem lenta ou ruim | Reduza `video.set_quality`; verifique o Wi-Fi. |
| Teste atingiu o drone real | Faltou o domínio isolado (seção 8). |

## 11. Limitações e o que não foi verificado

- **Pouco voo real**: só stick mode, decolagem, pouso, gimbal e giro. O resto é hipótese; em
  especial o **sentido do giro** (o app assume horário positivo no SDK; se girar ao contrário, o
  ajuste está em `MainActivity.subscribeToCommandTopics()`).
- **Gimbal do Mini SE**: só pitch. **Suporte por comando** varia: `system.probe` mostra o que responde.
- **Vídeo** reencodado, com atraso. **Sem autenticação nem criptografia** na ponte (seção 2.1).
- **Sem devolução automática do controle** depois do watchdog; o computador pode rearmar após um pânico.
- **Fora do escopo, de propósito**: pânico, motores, reiniciar, calibrações, exposição da câmera,
  formatar o SD, missões e baixar mídia. **Um piloto de cada vez**: sem arbitragem entre programas.
- Respostas diferentes por história: `system.probe` usa `result`, `flight.settings` usa `value`.

## 12. Estender o app Android

Uma linha em `DroneCommands.kt`, na função `register*` do assunto, e nada mais muda na ponte:

```kotlin
flightCmd("flight.set_xxx", "Faz xxx.", "valor:int") { fc, args, cb -> fc.setXxx(args.intArg("valor"), cb) }
cmd("flight.get_xxx", "Lê xxx.", probe = true) { _, r ->      // leitura sem argumentos: o system.probe a inclui
    val fc = aircraft?.flightController ?: return@cmd r.fail(NO_FLIGHT_CONTROLLER)
    fc.getXxx(valueCb(r))
}
```

Depois documente o comando no `PROTOCOLO.md` **e neste manual** (o `DocsCoverageTest` falha enquanto
faltar, entre crases, em um dos dois), recompile e **reinstale com Run**. Para algo contínuo (como
`cmd_vel`), crie um tópico em `MainActivity.subscribeToCommandTopics()` e siga o padrão do gimbal no
`DroneController`: guardar o valor (via `normalizedAxis`), repetir num laço e **zerar por watchdog**.

## Apêndice A — Cliente sem ROS (WebSocket direto)

Qualquer linguagem com WebSocket fala com o rosbridge no lugar do ROS2. Cada tópico precisa de
`advertise` antes do primeiro `publish` e de `subscribe` para receber; o `data` das mensagens
`std_msgs/String` é **uma string** com o JSON dentro:

```json
{"op": "subscribe", "topic": "/phone/rpc/response", "type": "std_msgs/String"}
{"op": "advertise", "topic": "/drone/rpc/request", "type": "std_msgs/String"}
{"op": "publish", "topic": "/drone/rpc/request", "msg": {"data": "{\"id\": \"meu-1\", \"cmd\": \"system.ping\"}"}}
{"op": "advertise", "topic": "/drone/stick_mode", "type": "std_msgs/Bool"}
{"op": "publish", "topic": "/drone/stick_mode", "msg": {"data": true}}
{"op": "advertise", "topic": "/drone/cmd_vel", "type": "geometry_msgs/Twist"}
{"op": "publish", "topic": "/drone/cmd_vel",
 "msg": {"linear": {"x": 0.2, "y": 0.0, "z": 0.0}, "angular": {"x": 0.0, "y": 0.0, "z": 0.0}}}
```

A resposta chega como `{"op": "publish", "topic": "/phone/rpc/response", "msg": {"data": "<JSON da resposta>"}}`.
As regras da seção 4 valem igual (20 Hz, desarmar ao sair).
