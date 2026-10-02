# Drone Bridge

🇧🇷 [Leia em português](README.pt-BR.md)

An Android app that **connects a DJI drone to a computer**. The phone is plugged into the DJI remote
controller; the computer sends flight, gimbal and camera commands over the network and gets telemetry
and the drone's camera feed back. The app has no control logic of its own: what flies the drone is
whatever program you run on the computer (keyboard, gamepad, computer vision, a script…).

> Tested only on a **DJI Mini SE**, through **DJI Mobile SDK v4**. Other aircraft supported by that
> SDK may work, but nothing here has been checked on them.

## ⚠️ Safety — read this first

Drones can injure people and damage property. **You fly at your own risk.**

- **First tests: propellers removed.** Then propeller guards, **low altitude, open area, away from people.**
- Keep the **physical remote controller in your hand** at all times. The app's **ASSUMIR CONTROLE (RC)**
  button hands control back to the remote sticks; the **PÂNICO** (panic) button stops the commands, makes
  the aircraft hover and cancels takeoff/landing/return-to-home. Panic exists only on the phone and can
  never be triggered or disabled from the computer. It does **not** cut the motors.
- The computer's **velocity** commands only take effect once it arms the **stick mode** (off by default);
  takeoff, landing, return to home, gimbal and camera commands do **not** need it. If the computer stops
  sending velocity commands for 500 ms, the app zeroes the sticks and the aircraft hovers.
- Follow the aviation rules of your country (line of sight, altitude limits, no-fly zones).
- The software is provided "as is", **with no warranty** (see [LICENSE](LICENSE)). The authors are not
  responsible for damage or injury.

This project is **not affiliated with, endorsed by or sponsored by DJI**. "DJI" and "Mini SE" are
trademarks of their owners.

## How it works

```
 your program ──ROS 2──▶ rosbridge ──WebSocket──▶ Drone Bridge ──DJI SDK──▶ remote ──▶ drone
 (computer)    topics    (computer)    Wi-Fi        (phone)         USB       controller   radio
      ▲                                                │
      └───────── telemetry, state, video, replies ─────┘
```

The phone connects **to** the computer's `rosbridge_websocket` (port 9090 by default).

What the computer can do:

- Send velocity commands (forward/sideways/up/yaw), gimbal pitch speed and arm/disarm the stick mode.
- Run named commands (RPC): take off, land, return to home, cancel a landing or return, flight
  settings, camera mode/photo/video, gimbal, video quality, and more.
- Receive telemetry, an on-demand state snapshot, and the drone's video (JPEG frames).

## Requirements

- **Phone:** Android 7.0+ (`minSdk 24`), connected by USB cable to the DJI remote controller.
- **Aircraft:** a DJI model supported by Mobile SDK v4 (tested: Mini SE).
- **Computer:** a ROS 2 setup with `rosbridge_server` (tested with ROS 2 Humble), on the same network as
  the phone. Any program that speaks the protocol works; see [PROTOCOLO.md](PROTOCOLO.md).
- **A DJI App Key** (see below) — each developer needs their own.

## Build

1. Create an App Key at <https://developer.dji.com/user/apps/>, registered for the exact
   `applicationId` in `app/build.gradle.kts` (`io.github.jeanpedrogl.dronebridge`). **If you fork this
   project, change the `applicationId` to your own** and register the key for it. The key is also tied
   to the keystore you sign with.
2. Put the key in a local, git-ignored file:
   ```bash
   cp secrets.properties.example secrets.properties   # then edit DJI_API_KEY
   ```
3. Build with the JDK bundled in Android Studio (`<android-studio>/jbr`):
   ```bash
   export JAVA_HOME=/path/to/android-studio/jbr
   ./gradlew :app:assembleDebug
   ```
   The APK is `app/build/outputs/apk/debug/app-debug.apk` (150–200 MB because of the DJI SDK's native libraries).
4. Install it with **Run (▶)** in Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   **Do not use "Apply Changes"**: it breaks the DJI SDK's protection layer and the app closes right after opening.

The DJI SDK is downloaded from Maven Central at build time and is **not** part of this repository. It is
subject to DJI's own terms; the App Key validation needs internet access the first time the app runs.

## Using it

1. On the computer, start rosbridge, e.g. `ros2 launch rosbridge_server rosbridge_websocket_launch.xml`.
2. Plug the phone into the powered-on remote controller, open the app and allow the permissions.
3. Tap the status line at the top of the app and enter the computer's `IP:9090`.
4. Run your program on the computer. To write one, start with
   [MANUAL_DESENVOLVEDOR.md](MANUAL_DESENVOLVEDOR.md) (Portuguese): it shows how each topic and command is used,
   with `ros2` command-line examples and a reference Python (`rclpy`) program.

## Documentation

| File | Content |
|---|---|
| [PROTOCOLO.md](PROTOCOLO.md) | Topics, RPC commands and state sections (the contract), in Portuguese |
| [MANUAL_DESENVOLVEDOR.md](MANUAL_DESENVOLVEDOR.md) | Guide to writing a computer-side program, in Portuguese |
| [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md) | Architecture notes and SDK pitfalls (for coding agents and contributors) |

## Limitations

- Tested on one aircraft, with a limited set of flights. The yaw direction conversion is still unconfirmed.
- The Mini SE gimbal only accepts pitch.
- Requires ROS 2/rosbridge on the computer. The WebSocket has **no authentication**: use a network you trust.
- The video sent to the computer is a re-encoded copy (JPEG), with delay and lower quality than the native stream.
- After a panic, the computer can arm the stick mode again: computer programs must treat the phone leaving
  stick mode as an order to stop (see the manual).
- The app screen is currently in Portuguese.

## License

[MIT](LICENSE) for the code in this repository. The DJI Mobile SDK and its App Key are covered by DJI's terms.
