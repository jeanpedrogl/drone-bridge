# Protocolo celular ↔ computador

Referência do contrato entre o app **Drone Bridge** (no celular) e o programa do computador. Para
aprender a escrever esse programa, com exemplos rápidos de cada tópico e comando, comece
pelo [`MANUAL_DESENVOLVEDOR.md`](MANUAL_DESENVOLVEDOR.md); este arquivo é a tabela de consulta.

O app é uma ponte genérica: o computador (qualquer programa ROS2, ou qualquer cliente de
WebSocket que fale rosbridge) pede coisas ao drone pelo celular, via rosbridge (porta 9090 por
padrão). O pânico **não** passa por aqui: existe só no celular.

> **Só parte disto foi exercitada com o drone real** (um DJI Mini SE: stick mode, decolagem,
> pouso, gimbal e giro). O restante compila e o despachante tem testes unitários, mas o que o
> aparelho aceita de fato só se descobre com ele ligado — rode `system.probe` (abaixo) primeiro.
> Em voo, siga as regras de segurança do [`MANUAL_DESENVOLVEDOR.md`](MANUAL_DESENVOLVEDOR.md).

Sumário: [1 Conexão](#1-conexão) · [2 Tópicos](#2-tópicos) · [3 Eixos, unidades e escala](#3-eixos-unidades-e-escala) ·
[4 Telemetria](#4-telemetria-phonetelemetria) · [5 Comandos RPC](#5-comandos-rpc) ·
[6 Referência dos comandos](#6-referência-dos-comandos) · [7 Estado](#7-estado-stateget-e-phonestate) ·
[8 Vídeo](#8-vídeo) · [9 Sem ROS](#9-cliente-sem-ros-websocket-direto) · [10 Estender](#10-como-adicionar-um-comando-novo-no-app)

## 1. Conexão

- O **celular é o cliente**: ele abre um WebSocket para `ws://IP:porta` do computador, onde roda o
  `rosbridge_websocket` (padrão do app: `10.42.0.1:9090`, configurável tocando na linha de status;
  sem porta, usa a 9090; um endereço inválido é recusado com um aviso e não é salvo). Se a conexão
  cair ou o rosbridge for reiniciado, o app tenta de novo a cada 3 s e **assina e anuncia tudo
  outra vez** sozinho.
- Do ponto de vista do ROS2, o celular é um nó comum: os tópicos `/phone/...` só passam a existir
  depois que ele conecta.
- **Não há autenticação nem criptografia.** Qualquer aparelho que alcance a porta do rosbridge
  pode publicar em `/drone/cmd_vel` e decolar o drone. Use uma rede sua (hotspot com senha) e,
  se quiser, restrinja os tópicos aceitos (veja o manual, seção 2). O que protege o voo é o
  watchdog de 500 ms, o stick mode desligado por padrão e o pânico do celular — não a rede.
- O app só aceita comandos de voo por velocidade com o **stick mode ligado** (`/drone/stick_mode`).
  Os comandos RPC, os atalhos de decolar/pousar e o gimbal funcionam sem ele.

## 2. Tópicos

| Tópico | Tipo | Sentido | Uso |
|---|---|---|---|
| `/drone/cmd_vel` | `geometry_msgs/Twist` | PC → celular | Velocidade contínua (REP 103, seção 3). Sem mensagem por 500 ms, o drone para. Só vale com o stick mode ligado. |
| `/drone/stick_mode` | `std_msgs/Bool` | PC → celular | `true` liga o controle por software; `false` devolve ao controle físico. |
| `/drone/gimbal_pitch_rate` | `std_msgs/Float32` | PC → celular | Inclinação do gimbal, −1..1 (positivo = sobe). Sem mensagem por 500 ms, para. Funciona sem stick mode. |
| `/drone/takeoff`, `/drone/land` | `std_msgs/Empty` | PC → celular | Atalhos de decolar/pousar, **sem resposta** (use `flight.take_off`/`flight.land` se quiser saber o resultado). |
| `/drone/rpc/request` | `std_msgs/String` (JSON) | PC → celular | Qualquer comando da seção 6. |
| `/phone/rpc/response` | `std_msgs/String` (JSON) | celular → PC | Resposta de cada comando. É **de todos** os programas ligados: confira o `id`. |
| `/phone/state` | `std_msgs/String` (JSON) | celular → PC | Estado do drone — **só se você pedir** (`state.stream_start`). |
| `/phone/telemetria` | `std_msgs/String` (JSON) | celular → PC | Resumo a 2 Hz (seção 4). |
| `/phone/drone_camera/compressed` | `sensor_msgs/CompressedImage` | celular → PC | Vídeo do drone em JPEG (seção 8). |

Regras de cada tópico de comando:

- **`/drone/stick_mode`**: publique só quando o estado **muda**. Cada `true` refaz a ativação do
  virtual stick no SDK (o app evita laços duplicados, mas há um instante de "parada" por vez). O
  app **recusa** o `true` se o drone não estiver conectado, se o app estiver em **segundo plano**
  (o piloto não vê o botão de pânico) ou se o SDK negar (se o modo já estava ligado, essa falha o
  desliga); a tela do celular mostra o motivo e a telemetria fica com `stick_mode: false`. O app
  também desliga o stick mode sozinho ao ir para segundo plano, ao desconectar o drone, no pânico e
  no botão ASSUMIR CONTROLE (RC).
  Mensagem sem o campo `data` conta como `false`.
- **`/drone/cmd_vel`**: valores fora de [−1, 1] são cortados no app e `NaN` vira 0. Mande a ~20 Hz
  enquanto armado; o app repete o último valor ao drone a cada 50 ms (o SDK exige 5–25 Hz) e, se
  passarem 500 ms sem mensagem, zera os valores e o drone **paira** (continua em stick mode).
- **`/drone/gimbal_pitch_rate`**: mesma regra de corte e de 500 ms. O gimbal mantém o ângulo
  quando a velocidade volta a zero e para sozinho nos limites mecânicos.
- **`/drone/takeoff`, `/drone/land`**: o conteúdo é ignorado (`{}`). O resultado só aparece na
  tela do celular.

## 3. Eixos, unidades e escala

Mande **sempre no padrão ROS (REP 103)**: `linear.x` = frente, `linear.y` = **esquerda**,
`linear.z` = cima, `angular.z` = giro **anti-horário** positivo. O app converte para o DJI
(direita e, por suposição ainda não confirmada em voo, horário positivos). Não "corrija" os sinais
no seu programa.

Todos os campos são **normalizados em [−1, 1]**; o valor 1,0 equivale ao limite configurado no app
(constantes no topo de `DroneController.kt`, não ao máximo do SDK):

| Eixo | 1,0 equivale a |
|---|---|
| `linear.x`, `linear.y` | 4 m/s |
| `linear.z` | 2 m/s |
| `angular.z` | 85 °/s |
| gimbal (`/drone/gimbal_pitch_rate`) | 50 °/s |

Nos primeiros testes use frações pequenas (0,2 a 0,3). O tempo limite sem comando é de **500 ms**.

## 4. Telemetria (`/phone/telemetria`)

JSON em `std_msgs/String.data`, publicado a cada 0,5 s **mesmo sem drone conectado**:

```json
{"drone_conectado": true, "stick_mode": false, "bateria_percent": 80,
 "altitude_m": 0.0, "velocidade_ms": 0.0, "satelites": 12, "voando": false}
```

| Campo | Tipo | Significado |
|---|---|---|
| `drone_conectado` | bool | O app enxerga o drone pelo controle remoto. |
| `stick_mode` | bool | O controle por software está **realmente** ligado (use para a regra 5 do manual). |
| `bateria_percent` | int ou `null` | Carga da bateria do drone. |
| `altitude_m` | float ou `null` | Altitude em metros (segundo a DJI, relativa ao ponto de decolagem). |
| `velocidade_ms` | float ou `null` | Velocidade horizontal (módulo), m/s. |
| `satelites` | int ou `null` | Satélites GPS. |
| `voando` | bool ou `null` | O drone está no ar. |

Um campo ainda desconhecido (nenhum dado do drone chegou) vem `null`. Para mais detalhes use o
estado (seção 7). Os nomes dos campos estão em português por motivos históricos.

## 5. Comandos RPC

Requisição, publicada em `/drone/rpc/request` (o campo `data` do `std_msgs/String` é este JSON):

```json
{"id": "a1b2-7", "cmd": "home.set_return_height", "args": {"meters": 30}}
```

Resposta, em `/phone/rpc/response`:

```json
{"id": "a1b2-7", "cmd": "home.set_return_height", "ok": true, "result": null}
{"id": "a1b2-7", "cmd": "home.set_return_height", "ok": false, "error": "texto do erro"}
```

- **`id`**: qualquer valor JSON (número ou texto); volta igual na resposta, e é o que casa pedido
  e resposta. Como `/phone/rpc/response` chega a **todos** os programas ligados, use ids **únicos
  por programa** (por exemplo `"<prefixo aleatório>-<contador>"`): dois programas contando a
  partir de 1 trocariam as respostas. Ignore respostas com ids que você não enviou.
- **`args`**: objeto; omita ou use `{}` se o comando não tem argumentos. Números chegam como
  inteiro/decimal, `true`/`false` como booleano (o app converte números escritos como texto, mas
  não dependa disso). Um argumento a mais é ignorado.
- **Sempre há exatamente uma resposta**: sucesso, erro do SDK/validação, ou
  `"Sem resposta do drone em 10s."` se o aparelho não retornar (componente ausente ou comando sem
  suporte). Uma resposta tardia do drone depois do prazo é descartada.
- **`ok: true` significa "o drone aceitou o comando", não "terminou"**. Decolar e pousar levam
  segundos: confirme pela telemetria (`voando`, `altitude_m`) ou pelo estado. Comandos de
  escrita respondem `result: null`.
- **Confirmação**: comandos marcados com **confirmar** só rodam se `args` tiver `"confirm": true`;
  sem isso a resposta é `'<cmd>' exige "confirm": true em args.`.
- **Tempos**: leitura/escrita simples responde em milissegundos; `flight.settings` pode levar até
  **6 s** (junta várias leituras) e `system.probe` até **8 s**. Use um prazo de cliente de ~12 s.

**Erros que o app produz** (texto em português; os do SDK da DJI vêm em inglês):

| Mensagem | Quando |
|---|---|
| `JSON inválido` | O `data` não é um JSON de objeto (o `id` volta `null`). |
| `Comando desconhecido: 'x'. Veja system.commands.` | Nome inexistente (APK desatualizado?). |
| `'x' exige "confirm": true em args.` | Faltou a confirmação. |
| `Falta o argumento 'k'.` | Argumento obrigatório ausente. |
| `'k' inválido: 'v'. Use um de: A, B, C` | Valor de enum fora da lista (maiúsculas/minúsculas não importam). |
| `Value x at k of type ... cannot be converted to int` | Tipo errado (texto do Android, em inglês). |
| `Controle de voo indisponível (drone não conectado?).` · `Gimbal indisponível …` · `Câmera indisponível …` | O componente não está presente. |
| `Drone não conectado.` | `flight.take_off`/`flight.land` sem drone. |
| `Sem resposta do drone em 10s.` | Tempo esgotado. |
| (texto do SDK) | Falha do drone, por exemplo valor fora da faixa ou ação inválida no estado atual. |

## 6. Referência dos comandos

`system.commands` devolve esta mesma lista (com os argumentos) direto do app — e é a fonte de
verdade se este arquivo ficar para trás. **Resultado** é o campo `result` quando `ok` é verdadeiro;
`—` significa `null`.

### Sistema e estado

| Comando | Argumentos | Resultado | Efeito |
|---|---|---|---|
| `system.ping` | — | `"pong"` | Testa a ligação. |
| `system.commands` | — | lista de `{cmd, description, args, requires_confirm, probe}` | Lista os comandos registrados. |
| `system.info` | — | `{connected, model, firmware, has_flight_controller, has_gimbal, has_camera, has_battery, has_remote_controller}` | Modelo, firmware e componentes presentes (`model`/`firmware` podem ser `null`). |
| `system.probe` | — | `{<comando>: {ok, result \| error}}` | Roda todas as leituras sem argumentos e diz quais o drone respondeu e quais recusou. Até 8 s. |
| `state.get` | `sections?` | objeto de estado (seção 7) | Um retrato do estado. |
| `state.stream_start` | `interval_ms?` (padrão 1000, mínimo 100), `sections?` | — | Publica o estado em `/phone/state` periodicamente. Chamar de novo reconfigura. |
| `state.stream_stop` | — | — | Para a publicação. (Também para sozinha se o PC desconectar.) |

### Vídeo enviado ao computador

| Comando | Argumentos | Resultado | Efeito |
|---|---|---|---|
| `video.set_quality` | `fps?` (1–30), `max_width?` (160–1920), `jpeg_quality?` (10–95) | `{fps, max_width, jpeg_quality}` (valores em vigor) | Ajusta o vídeo reenviado (padrão: 8 fps, 640 px, qualidade 50). Omitido = mantém. Valor fora da faixa: erro (`fps deve estar entre 1 e 30`...) e nada muda. |
| `video.get` | — | `{fps, max_width, jpeg_quality}` | Lê os ajustes atuais. |

### Voo

| Comando | Argumentos | Resultado | Efeito |
|---|---|---|---|
| `flight.take_off` | — | — | Decola. |
| `flight.land` | — | — | Pousa; se o drone está esperando confirmação de pouso (`landing_confirmation_needed`), confirma. |
| `flight.cancel_takeoff` | — | — | Cancela uma decolagem em andamento (erro se não houver). |
| `flight.cancel_landing` | — | — | Cancela um pouso em andamento (erro se não houver). |
| `flight.cancel_land_or_rth` | — | — | Cancela um pouso **ou** um retorno para casa em andamento (nunca a decolagem), escolhendo pelo estado do voo e tentando os dois se o estado não ajudar. Erro `Nada para cancelar: ...` se não houver nenhum. |
| `flight.settings` | — | `{<ajuste>: {ok, value \| error}}` | Lê: `home_return_height_m`, `max_height_m`, `max_radius_m`, `radius_limit_enabled`, `low_battery_threshold_percent`, `serious_low_battery_threshold_percent`, `failsafe_behavior` (`HOVER`/`LANDING`/`GO_HOME`), `smart_rth_enabled`, `novice_mode`. Cada item que o drone não responde vem `{ok:false, error}`. Até 6 s. |
| `flight.set_max_height` | `meters` (int) | — | Altura máxima de voo. |
| `flight.set_max_radius` | `meters` (int) | — | Raio máximo a partir de casa. |
| `flight.set_radius_limit` | `enabled` (bool) | — | Liga/desliga o limite de raio. |
| `flight.set_low_battery_threshold` | `percent` (int) | — | Aviso de bateria baixa. |
| `flight.set_serious_low_battery_threshold` | `percent` (int) | — | Bateria crítica (o drone age sozinho). |
| `flight.set_failsafe` | `behavior`: `HOVER` \| `LANDING` \| `GO_HOME` | — | **confirmar** — o que o drone faz ao perder o sinal do controle. |

As faixas aceitas pelos `flight.set_*` são as do SDK: o app não as valida, o drone responde com erro.

### Retorno para casa

| Comando | Argumentos | Resultado | Efeito |
|---|---|---|---|
| `home.go_home` | — | — | Inicia o retorno para casa. |
| `home.cancel_go_home` | — | — | Cancela o retorno. |
| `home.set_here` | — | — | Casa = posição atual do drone. |
| `home.set_location` | `latitude`, `longitude` (graus) | — | Casa em coordenadas (erro `Coordenadas inválidas` se fora do globo). |
| `home.set_return_height` | `meters` (int) | — | Altura em que ele sobe antes de voltar. |
| `home.set_smart_rth` | `enabled` (bool) | — | Retorno inteligente por bateria. |
| `home.get` | — | `{latitude, longitude, valid}` | Lê a casa registrada. |

### Gimbal

| Comando | Argumentos | Resultado | Efeito |
|---|---|---|---|
| `gimbal.rotate` | `mode`: `SPEED` \| `RELATIVE_ANGLE` \| `ABSOLUTE_ANGLE`; `pitch?`, `roll?`, `yaw?` (graus, ou °/s em `SPEED`); `time?` (s, só nos modos de ângulo) | — | Gira. Eixo omitido fica parado. |
| `gimbal.reset` | — | — | Posição inicial. |
| `gimbal.set_mode` | `mode`: `FREE` \| `FPV` \| `YAW_FOLLOW` | — | Modo do gimbal. |
| `gimbal.set_pitch_range_extension` | `enabled` (bool) | — | Estende a inclinação para cima (Mini SE: de 0° para +20°). |
| `gimbal.get_pitch_range_extension` | — | `true`/`false` | Lê se a extensão está ligada. |
| `gimbal.capabilities` | — | `{<CHAVE>: bool}`, por exemplo `{"ADJUST_PITCH": true, ...}` | O que este gimbal aceita. |

**Limites do Mini SE.** Segundo a DJI, só a **inclinação (pitch)** é controlável: −90° a 0°, ou
até +20° com `gimbal.set_pitch_range_extension`. Giro (yaw) e rolagem (roll) são estabilizados
sozinhos e não aceitam comando; o SDK não recusa um comando de yaw/roll antecipadamente, então o
programa do computador deve usar só **pitch** e consultar `gimbal.capabilities` ao iniciar. Para
movimento contínuo prefira o tópico `/drone/gimbal_pitch_rate` (50 °/s no valor 1,0, com
watchdog). **`gimbal.rotate` em `SPEED` não tem watchdog no app**: ele não para sozinho por falta
de mensagens — mande `pitch: 0` para parar.

### Câmera

| Comando | Argumentos | Resultado | Efeito |
|---|---|---|---|
| `camera.set_mode` | `mode`: `SHOOT_PHOTO` \| `RECORD_VIDEO` | — | Modo foto/vídeo. |
| `camera.get_mode` | — | nome do modo: `"SHOOT_PHOTO"`, `"RECORD_VIDEO"` ou outro do SDK (`PLAYBACK`, `MEDIA_DOWNLOAD`, `BROADCAST`, `UNKNOWN`) | Modo atual. |
| `camera.start_photo` | — | — | Tira uma foto (modo `SHOOT_PHOTO`). |
| `camera.stop_photo` | — | — | Interrompe a foto (só serve nos modos de intervalo). |
| `camera.start_record` | — | — | Começa a gravar (modo `RECORD_VIDEO`). |
| `camera.stop_record` | — | — | Para a gravação. |

Ajustes de exposição, formato, resolução e a formatação do cartão **não** foram implementados.
O resultado de `camera.start_photo` é "aceito"; confirme a foto/gravação em `state.get` (`camera`).

### O que **não** existe, de propósito

Pânico, ligar/desligar motores, reiniciar, calibrações, exposição da câmera, formatar o cartão
SD, missões (waypoint, órbita, seguir) e baixar mídia do cartão. Nenhum comando RPC altera o
pânico nem o botão ASSUMIR CONTROLE (RC).

## 7. Estado (`state.get` e `/phone/state`)

`sections` aceita qualquer combinação de `product`, `flight`, `gimbal`, `camera`, `battery`,
`remote` (omitido = todas; nome desconhecido = erro `Seções desconhecidas: ...`). O resultado é
um objeto com `stamp_ms` (relógio do **celular**, ms desde 1970) e uma chave por seção pedida. Uma
seção sem dados ainda (componente que não respondeu ou que o drone não tem) vem `null`. Valores
não finitos viram `null`; enums vêm pelo nome. O estado só é enviado quando você pede: com
`state.stream_start` ele chega em `/phone/state` (o mesmo objeto), até `state.stream_stop` ou até
o computador desconectar.

- `product`: `model`.
- `flight`: `location` (`latitude`, `longitude`, `altitude_m`), `attitude_deg` (`pitch`, `roll`, `yaw`), `velocity_ms` (`x`, `y`, `z`), `heading_deg`, `satellites`, `gps_signal`, `flight_mode` (nome do enum) e `flight_mode_text`, `is_flying`, `motors_on`, `flight_time_s`, `ultrasonic_height_m`, `ultrasonic_in_use`, `vision_positioning_in_use`, `imu_preheating`, `home` (`is_set`, `latitude`, `longitude`, `return_height_m`), `going_home`, `go_home_state`, `landing_confirmation_needed`, `low_battery_warning`, `serious_low_battery_warning`, `wind_warning`, `reached_max_height`, `reached_max_radius`.
- `gimbal`: `attitude_deg` (`pitch`, `roll`, `yaw`), `mode`, `pitch_at_stop`, `roll_at_stop`, `yaw_at_stop`, `yaw_relative_to_aircraft_deg`.
- `camera`: `mode`, `is_recording`, `recording_time_s`, `shooting_single_photo`, `shooting_burst_photo`, `shooting_interval_photo`, `storing_photo`, `overheating`, `has_error`, `exposure` (`iso`, `shutter_speed`, `aperture`, `compensation`; ou `null`), `storage` (`location`, `inserted`, `full`, `formatting`, `has_error`, `total_mb`, `remaining_mb`, `photos_left`, `recording_s_left`; ou `null`).
- `battery`: `percent`, `voltage_mv`, `current_ma`, `temperature_c`, `charge_remaining_mah`, `full_charge_capacity_mah`, `discharges`, `charging`.
- `remote`: `left_stick` e `right_stick` (`h`, `v`: posição do stick no valor bruto do SDK, em geral de −660 a 660) e `flight_mode_switch`.

**Atenção aos referenciais.** `velocity_ms` está no referencial **NED** do SDK (x norte, y leste,
z **para baixo**) — *não* no do corpo do drone, e não no padrão REP 103 do `cmd_vel`. `yaw` e
`heading_deg` são graus em relação ao norte. Os nomes de enum (`flight_mode`, `go_home_state`,
`wind_warning`, `gps_signal`...) são os do SDK da DJI v4; a lista completa está na documentação
dele.

## 8. Vídeo

`/phone/drone_camera/compressed` (`sensor_msgs/CompressedImage`): `format` = `"jpeg"`, `data` =
bytes do JPEG (o rosbridge decodifica o base64 do WebSocket antes de chegar a um assinante
ROS2), `header.frame_id` = `"celular"`, `header.stamp` = instante da captura no relógio do
**celular** (que não é sincronizado com o do computador).

É uma **cópia reencodada** (redimensionada e recomprimida no celular), não o fluxo do drone: com
`video.set_quality` você troca qualidade por tráfego no Wi-Fi e CPU do celular, **sem** afetar o
link do drone com o controle. Mais fps, pixels e qualidade = imagem melhor e muito mais tráfego
(aprox. proporcional a fps × tamanho do quadro). Se o Wi-Fi não acompanha (mais de ~256 KB à
espera de envio), o app **descarta** quadros em vez de enfileirá-los, para a imagem não ficar
atrasada: o fps efetivo pode ser menor que o configurado. Use fila de tamanho 1 ao assinar e
guarde só o quadro mais recente.

## 9. Cliente sem ROS (WebSocket direto)

Qualquer linguagem com WebSocket pode falar com o rosbridge no lugar do ROS2 (as mensagens são as
mesmas do celular). Cada tópico precisa de `advertise` antes do primeiro `publish`, e de
`subscribe` para receber:

```json
{"op": "subscribe", "topic": "/phone/rpc/response", "type": "std_msgs/String"}
{"op": "advertise", "topic": "/drone/rpc/request", "type": "std_msgs/String"}
{"op": "publish", "topic": "/drone/rpc/request",
 "msg": {"data": "{\"id\": \"meu-1\", \"cmd\": \"state.get\", \"args\": {\"sections\": [\"battery\"]}}"}}
```

A resposta chega como `{"op": "publish", "topic": "/phone/rpc/response", "msg": {"data": "<JSON da resposta>"}}`
— note que o `data` é **uma string** com o JSON dentro. Os comandos de velocidade seguem o mesmo
formato, por exemplo
`{"op":"publish","topic":"/drone/cmd_vel","msg":{"linear":{"x":0.2,"y":0,"z":0},"angular":{"x":0,"y":0,"z":0}}}`.

## 10. Como adicionar um comando novo no app

Em `DroneCommands.kt`, dentro da função `register*` do assunto, uma linha como
`flightCmd("flight.set_xxx", "descrição", "campo:tipo") { fc, args, cb -> fc.setXxx(args.intArg("campo"), cb) }`
(use `cmd(...)` para leituras, e `probe = true` se ela não precisar de argumentos). Nada mais no app muda.

Documente o comando **neste arquivo e no manual** (seção 6, com uma linha de exemplo): o teste
`DocsCoverageTest` falha enquanto algum comando registrado não aparecer, entre crases, nos dois
documentos.
