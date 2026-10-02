# Drone Bridge

🇬🇧 [Read in English](README.md)

Aplicativo Android que **conecta um drone DJI a um computador**. O celular fica ligado ao controle
remoto da DJI; o computador manda comandos de voo, gimbal e câmera pela rede e recebe de volta a
telemetria e o vídeo da câmera do drone. O app não tem lógica de controle própria: quem pilota é o
programa que você rodar no computador (teclado, gamepad, visão computacional, um script…).

> Testado apenas em um **DJI Mini SE**, pelo **DJI Mobile SDK v4**. Outras aeronaves suportadas por esse
> SDK podem funcionar, mas nada aqui foi verificado nelas.

## ⚠️ Segurança — leia antes de usar

Drones podem ferir pessoas e causar danos. **Você voa por sua conta e risco.**

- **Primeiros testes: sem hélices.** Depois com protetor de hélices, **baixo, em área aberta e longe de pessoas.**
- Mantenha o **controle remoto físico na mão** o tempo todo. O botão **ASSUMIR CONTROLE (RC)** do app
  devolve o comando aos sticks do controle; o botão **PÂNICO** interrompe os comandos, faz a aeronave
  pairar e cancela decolagem/pouso/retorno para casa. O pânico existe só no celular e o computador nunca
  consegue acioná-lo nem desativá-lo. Ele **não** corta os motores.
- Os comandos de **velocidade** do computador só têm efeito depois que ele arma o **stick mode** (desligado
  por padrão); decolar, pousar, voltar para casa, gimbal e câmera **não** dependem dele. Se o computador
  parar de mandar velocidade por 500 ms, o app zera os sticks e a aeronave paira.
- Siga as regras de aviação do seu país (linha de visão, altura máxima, zonas proibidas).
- O software é fornecido "como está", **sem garantia** (veja [LICENSE](LICENSE)). Os autores não se
  responsabilizam por danos ou ferimentos.

Este projeto **não é afiliado, endossado nem patrocinado pela DJI**. "DJI" e "Mini SE" são marcas de
seus respectivos donos.

## Como funciona

```
 seu programa ──ROS 2──▶ rosbridge ──WebSocket──▶ Drone Bridge ──SDK DJI──▶ controle ──▶ drone
 (computador)  tópicos  (computador)    Wi-Fi       (celular)       USB        remoto      rádio
      ▲                                                │
      └───────── telemetria, estado, vídeo, respostas ─┘
```

O celular se conecta **ao** `rosbridge_websocket` do computador (porta 9090 por padrão).

O que o computador pode fazer:

- Mandar velocidades (frente/lado/subir/giro), velocidade de inclinação do gimbal e armar/desarmar o stick mode.
- Executar comandos por nome (RPC): decolar, pousar, voltar para casa, cancelar pouso ou retorno,
  ajustes de voo, modo/foto/vídeo da câmera, gimbal, qualidade do vídeo e outros.
- Receber telemetria, um retrato do estado sob demanda e o vídeo do drone (quadros JPEG).

## Requisitos

- **Celular:** Android 7.0+ (`minSdk 24`), ligado por cabo USB ao controle remoto da DJI.
- **Aeronave:** um modelo DJI suportado pelo Mobile SDK v4 (testado: Mini SE).
- **Computador:** ROS 2 com `rosbridge_server` (testado com ROS 2 Humble), na mesma rede do celular.
  Qualquer programa que fale o protocolo serve; veja o [PROTOCOLO.md](PROTOCOLO.md).
- **Uma App Key da DJI** (abaixo) — cada desenvolvedor precisa da sua.

## Compilar

1. Crie uma App Key em <https://developer.dji.com/user/apps/>, registrada para o `applicationId` exato de
   `app/build.gradle.kts` (`io.github.jeanpedrogl.dronebridge`). **Se você fizer um fork, troque o
   `applicationId` pelo seu** e registre a chave para ele. A chave também fica ligada ao keystore de assinatura.
2. Coloque a chave em um arquivo local, ignorado pelo git:
   ```bash
   cp secrets.properties.example secrets.properties   # depois edite DJI_API_KEY
   ```
3. Compile com o JDK que vem no Android Studio (`<android-studio>/jbr`):
   ```bash
   export JAVA_HOME=/caminho/do/android-studio/jbr
   ./gradlew :app:assembleDebug
   ```
   O APK fica em `app/build/outputs/apk/debug/app-debug.apk` (150–200 MB por causa das bibliotecas nativas do SDK da DJI).
4. Instale com **Run (▶)** no Android Studio ou `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   **Não use "Apply Changes"**: ele quebra a camada de proteção do SDK da DJI e o app fecha logo após abrir.

O SDK da DJI é baixado do Maven Central na compilação e **não** faz parte deste repositório. Ele segue os
termos da própria DJI; a validação da App Key precisa de internet na primeira vez que o app roda.

## Usando

1. No computador, inicie o rosbridge, por exemplo `ros2 launch rosbridge_server rosbridge_websocket_launch.xml`.
2. Ligue o celular ao controle remoto ligado, abra o app e aceite as permissões.
3. Toque na linha de status no topo do app e digite o `IP:9090` do computador.
4. Rode seu programa no computador. Para escrever um, comece pelo
   [MANUAL_DESENVOLVEDOR.md](MANUAL_DESENVOLVEDOR.md): ele mostra como usar cada tópico e comando, com exemplos
   em linha de comando (`ros2`) e um programa Python (`rclpy`) de referência.

## Documentação

| Arquivo | Conteúdo |
|---|---|
| [PROTOCOLO.md](PROTOCOLO.md) | Tópicos, comandos RPC e seções de estado (o contrato) |
| [MANUAL_DESENVOLVEDOR.md](MANUAL_DESENVOLVEDOR.md) | Guia para escrever um programa do computador |
| [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md) | Notas de arquitetura e armadilhas do SDK (para agentes de código e colaboradores), em inglês |

## Limitações

- Testado em uma aeronave, com um conjunto limitado de voos. A conversão do sentido do giro ainda não foi confirmada.
- O gimbal do Mini SE só aceita inclinação (pitch).
- Exige ROS 2/rosbridge no computador. O WebSocket **não tem autenticação**: use uma rede de confiança.
- O vídeo enviado ao computador é uma cópia reencodada (JPEG), com atraso e qualidade menor que o vídeo nativo.
- Depois de um pânico, o computador consegue armar o stick mode de novo: o programa do computador deve tratar
  a saída do stick mode no celular como ordem de parar (veja o manual).

## Licença

[MIT](LICENSE) para o código deste repositório. O SDK da DJI e a App Key seguem os termos da DJI.
