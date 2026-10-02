package io.github.jeanpedrogl.dronebridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dji.common.error.DJIError
import dji.common.flightcontroller.FlightControllerState
import dji.common.flightcontroller.virtualstick.FlightControlData
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import kotlin.math.hypot

private const val TAG = "DroneController"

// Stick limits. The SDK accepts up to ±15 m/s (horizontal), ±4 m/s (vertical) and ±100 °/s (yaw);
// these are deliberately conservative. Raise them once you trust the setup.
private const val MAX_HORIZONTAL_MS = 4f
private const val MAX_VERTICAL_MS = 2f
private const val MAX_YAW_DPS = 85f

// Gimbal pitch speed for a full-scale (±1) command. The gimbal stops by itself at its mechanical
// limits, so this only bounds how fast the camera sweeps.
private const val MAX_GIMBAL_DPS = 50f

// The SDK expects virtual stick data at 5–25 Hz, otherwise the aircraft drops out of the mode.
private const val STICK_PERIOD_MS = 50L

// If no velocity command arrives from the computer bridge within this window (stalled Wi-Fi,
// the computer program crashed, etc.), the sticks are zeroed so the aircraft hovers instead of coasting
// on the last command it received.
private const val COMMAND_TIMEOUT_MS = 500L

/**
 * Clamps a normalized stick/gimbal value to [-1, 1]; NaN (which `coerceIn` would let through) becomes 0.
 * The computer is told to send clean values, but a bad one must never reach the aircraft as a speed.
 */
internal fun normalizedAxis(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(-1f, 1f)

data class Telemetry(
    val altitudeM: Float,
    val speedMs: Float,
    val satellites: Int,
    val isFlying: Boolean,
    val landingConfirmationNeeded: Boolean,
)

/**
 * Wraps the DJI flight controller: virtual stick streaming, takeoff/landing and the panic action.
 * All [Listener] callbacks and all public methods are on the main thread.
 */
class DroneController(private val listener: Listener, private val states: DeviceStates) {

    interface Listener {
        fun onMessage(message: String)
        fun onTelemetry(telemetry: Telemetry)
        fun onBattery(percent: Int)
        fun onStickModeChanged(enabled: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())

    // Stick values in SDK units. Written by the UI, read by the streaming loop (both main thread).
    private var pitch = 0f
    private var roll = 0f
    private var yaw = 0f
    private var throttle = 0f

    private var stickModeEnabled = false

    // Monotonic time (SystemClock.elapsedRealtime, immune to clock changes) of the last
    // setVelocityCommand() call; see COMMAND_TIMEOUT_MS.
    private var lastCommandAtMs = 0L

    // Gimbal pitch speed in SDK units (deg/s) and the time of the last setGimbalRate() call. The
    // gimbal loop only runs while the speed is non-zero; see gimbalLoop.
    private var gimbalRate = 0f
    private var lastGimbalCommandAtMs = 0L
    private var gimbalLoopRunning = false

    // True from the moment stick mode is requested until it is turned off again (computer, phone
    // button, panic, backgrounding, disconnect). Lets a slow "enable" reply that arrives after a panic
    // undo itself, while two enable requests in a row (the computer repeating `true`) both stand.
    private var stickWanted = false

    // False while the app is in the background: stick mode must not be armed then (see onAppPaused).
    private var inForeground = true
    private var lastTelemetry: Telemetry? = null
    private var lastStickError: String? = null

    private val flightController: FlightController?
        get() = (DJISDKManager.getInstance().product as? Aircraft)?.flightController

    private val stickLoop = object : Runnable {
        override fun run() {
            if (!stickModeEnabled) return
            if (SystemClock.elapsedRealtime() - lastCommandAtMs > COMMAND_TIMEOUT_MS) zeroSticks()
            flightController?.sendVirtualStickFlightControlData(
                FlightControlData(pitch, roll, yaw, throttle), stickCallback
            )
            main.postDelayed(this, STICK_PERIOD_MS)
        }
    }

    // Same watchdog as the sticks: a stale command stops the camera instead of letting it sweep to
    // the end stop. The loop sends one final zero-speed command and then stops itself.
    private val gimbalLoop = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastGimbalCommandAtMs > COMMAND_TIMEOUT_MS) gimbalRate = 0f
            sendGimbalSpeed(gimbalRate)
            if (gimbalRate == 0f) {
                gimbalLoopRunning = false
            } else {
                main.postDelayed(this, STICK_PERIOD_MS)
            }
        }
    }

    // Sent every 50 ms while the camera moves: log failures only, not 20 "ok" lines a second.
    private val gimbalCallback = CommonCallbacks.CompletionCallback<DJIError> { error ->
        if (error != null) Log.w(TAG, "Girar gimbal falhou: ${error.description}")
    }

    private val stickCallback = CommonCallbacks.CompletionCallback<DJIError> { error ->
        val description = error?.description
        if (description != lastStickError) {
            lastStickError = description
            if (description != null) {
                Log.e(TAG, "Virtual stick: $description")
                main.post { listener.onMessage("Erro no stick: $description") }
            }
        }
    }

    /** Hooks the state/battery callbacks. Safe to call repeatedly as components connect. */
    fun attach() {
        val aircraft = DJISDKManager.getInstance().product as? Aircraft ?: return
        aircraft.flightController?.setStateCallback { state ->
            states.flight = state
            main.post { publish(state) }
        }
        aircraft.battery?.setStateCallback { state ->
            states.battery = state
            main.post { listener.onBattery(state.chargeRemainingInPercent) }
        }
        states.attach(aircraft)
    }

    /** Drops local state after the aircraft/remote disconnects. */
    fun detach() {
        stickWanted = false
        gimbalRate = 0f
        main.removeCallbacks(gimbalLoop)
        gimbalLoopRunning = false
        stopStickLoop()
        stickModeEnabled = false
        lastTelemetry = null
        states.clear()
        listener.onStickModeChanged(false)
    }

    /**
     * Velocity command from the computer, forwarded by the ROS2 bridge (`RosBridgeClient`).
     * `forward`/`right`/`up`/`yawRate` are all normalized to [-1, 1]; resets the watchdog that
     * would otherwise hover the aircraft after [COMMAND_TIMEOUT_MS] of silence.
     */
    fun setVelocityCommand(forward: Float, right: Float, up: Float, yawRate: Float) {
        // DJI naming quirk: in VELOCITY mode "pitch" is the speed along the body Y axis (right)
        // and "roll" the speed along the body X axis (forward) — the opposite of the names.
        pitch = normalizedAxis(right) * MAX_HORIZONTAL_MS
        roll = normalizedAxis(forward) * MAX_HORIZONTAL_MS
        throttle = normalizedAxis(up) * MAX_VERTICAL_MS
        yaw = normalizedAxis(yawRate) * MAX_YAW_DPS
        lastCommandAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Gimbal pitch speed from the computer, normalized to [-1, 1] (positive = camera tilts up).
     * Works independently of stick mode — the camera is not part of the flight controls — but has
     * the same [COMMAND_TIMEOUT_MS] watchdog. The gimbal keeps its angle once the speed is zero.
     */
    fun setGimbalRate(pitchRate: Float) {
        gimbalRate = normalizedAxis(pitchRate) * MAX_GIMBAL_DPS
        lastGimbalCommandAtMs = SystemClock.elapsedRealtime()
        if (gimbalRate != 0f && !gimbalLoopRunning) {
            gimbalLoopRunning = true
            main.post(gimbalLoop)
        }
    }

    private fun stopGimbal() {
        gimbalRate = 0f
        main.removeCallbacks(gimbalLoop)
        gimbalLoopRunning = false
        sendGimbalSpeed(0f)
    }

    private fun sendGimbalSpeed(pitchDps: Float) {
        val gimbal = DJISDKManager.getInstance().product?.gimbal ?: return
        // In SPEED mode the unused axes must be 0 (NO_ROTATION is for the angle modes).
        val rotation = Rotation.Builder().mode(RotationMode.SPEED).pitch(pitchDps).roll(0f).yaw(0f).build()
        gimbal.rotate(rotation, gimbalCallback)
    }

    fun setStickMode(enabled: Boolean) {
        if (!enabled) {
            disableStickMode("Stick mode desligado. Controle remoto físico ativo.")
            return
        }
        if (!inForeground) {
            listener.onMessage("App em segundo plano: stick mode recusado. Abra o app e arme de novo.")
            listener.onStickModeChanged(false)
            return
        }
        val fc = flightController
        if (fc == null) {
            listener.onMessage("Drone não conectado.")
            listener.onStickModeChanged(false)
            return
        }
        zeroSticks()
        stickWanted = true
        fc.setVirtualStickAdvancedModeEnabled(true)
        fc.setRollPitchControlMode(RollPitchControlMode.VELOCITY)
        fc.setYawControlMode(YawControlMode.ANGULAR_VELOCITY)
        fc.setVerticalControlMode(VerticalControlMode.VELOCITY)
        fc.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY)
        fc.setVirtualStickModeEnabled(true, callback("Ativar virtual stick") { error ->
            if (!stickWanted) {
                // Panic/disable happened while this request was in flight: keep it off.
                if (error == null) fc.setVirtualStickModeEnabled(false, callback("Desfazer ativação"))
            } else if (error == null) {
                stickModeEnabled = true
                lastStickError = null
                // lastCommandAtMs is NOT reset here: the watchdog counts from the last real command,
                // so a command that went stale while the SDK was enabling the mode isn't revived.
                // Exactly one loop, even if this is a repeated enable: the SDK wants 5–25 Hz.
                main.removeCallbacks(stickLoop)
                main.post(stickLoop)
                listener.onStickModeChanged(true)
                listener.onMessage("Stick mode ligado. Controle pelo computador.")
            } else {
                // Also covers a repeated enable failing while the mode was already on: turn it off
                // for real, instead of reporting "off" (and greying out ASSUMIR CONTROLE) while the
                // stick loop keeps steering.
                disableStickMode("Não foi possível ligar o stick mode: ${error.description}")
            }
        })
    }

    /** [onDone] gets null on success or the failure description (used by the computer's RPC reply). */
    fun takeOff(onDone: ((String?) -> Unit)? = null) {
        val fc = flightController
        if (fc == null) {
            listener.onMessage("Drone não conectado.")
            onDone?.invoke("Drone não conectado.")
            return
        }
        fc.startTakeoff(callback("Decolar") { error ->
            listener.onMessage(
                if (error == null) "Decolando..." else "Falha ao decolar: ${error.description}"
            )
            onDone?.invoke(error?.description)
        })
    }

    /** Lands, or confirms the landing when the aircraft is waiting for confirmation near the ground. */
    fun land(onDone: ((String?) -> Unit)? = null) {
        val fc = flightController
        if (fc == null) {
            listener.onMessage("Drone não conectado.")
            onDone?.invoke("Drone não conectado.")
            return
        }
        if (lastTelemetry?.landingConfirmationNeeded == true) {
            fc.confirmLanding(callback("Confirmar pouso") { error ->
                listener.onMessage(
                    if (error == null) "Pouso confirmado." else "Falha ao confirmar: ${error.description}"
                )
                onDone?.invoke(error?.description)
            })
        } else {
            fc.startLanding(callback("Pousar") { error ->
                listener.onMessage(
                    if (error == null) "Pousando..." else "Falha ao pousar: ${error.description}"
                )
                onDone?.invoke(error?.description)
            })
        }
    }

    /**
     * Stops everything: zeroes the sticks, leaves virtual stick mode (the aircraft brakes and
     * hovers, and the physical remote takes over) and cancels any takeoff, landing or go-home.
     * It does NOT cut the motors, so the aircraft never falls out of the sky.
     */
    fun panic() {
        stickWanted = false
        zeroSticks()
        stopGimbal()
        val fc = flightController
        stopStickLoop()
        if (fc == null) {
            stickModeEnabled = false
            listener.onStickModeChanged(false)
            listener.onMessage("PÂNICO: sem conexão com o drone!")
            return
        }
        if (stickModeEnabled) {
            fc.sendVirtualStickFlightControlData(FlightControlData(0f, 0f, 0f, 0f), stickCallback)
        }
        stickModeEnabled = false
        fc.setVirtualStickModeEnabled(false, callback("Panico: desligar virtual stick"))
        fc.cancelTakeoff(callback("Panico: cancelar decolagem"))
        fc.cancelLanding(callback("Panico: cancelar pouso"))
        fc.cancelGoHome(callback("Panico: cancelar RTH"))
        listener.onStickModeChanged(false)
        listener.onMessage("PÂNICO! Drone parando (hover). Controle remoto físico ativo.")
    }

    /**
     * Call when the app leaves the foreground: the pilot can no longer see the panic button, so
     * stop steering — and refuse to arm again until [onAppResumed], even if the computer asks.
     */
    fun onAppPaused() {
        inForeground = false
        stopGimbal()
        if (stickModeEnabled || stickWanted) disableStickMode("Stick mode desligado (app em segundo plano).")
    }

    fun onAppResumed() {
        inForeground = true
    }

    private fun disableStickMode(message: String) {
        val wasEnabled = stickModeEnabled
        stickWanted = false
        zeroSticks()
        stopStickLoop()
        stickModeEnabled = false
        val fc = flightController
        if (wasEnabled) {
            fc?.sendVirtualStickFlightControlData(FlightControlData(0f, 0f, 0f, 0f), stickCallback)
        }
        fc?.setVirtualStickModeEnabled(false, callback("Desativar virtual stick"))
        listener.onStickModeChanged(false)
        listener.onMessage(message)
    }

    private fun stopStickLoop() = main.removeCallbacks(stickLoop)

    private fun zeroSticks() {
        pitch = 0f
        roll = 0f
        yaw = 0f
        throttle = 0f
    }

    private fun publish(state: FlightControllerState) {
        val altitude = state.aircraftLocation?.altitude?.takeUnless { it.isNaN() } ?: 0f
        val telemetry = Telemetry(
            altitudeM = altitude,
            speedMs = hypot(state.velocityX, state.velocityY),
            satellites = state.satelliteCount,
            isFlying = state.isFlying,
            landingConfirmationNeeded = state.isLandingConfirmationNeeded,
        )
        lastTelemetry = telemetry
        listener.onTelemetry(telemetry)
    }

    /** Completion callback that logs the outcome and optionally reports back on the main thread. */
    private fun callback(
        action: String,
        onDone: ((DJIError?) -> Unit)? = null,
    ) = CommonCallbacks.CompletionCallback<DJIError> { error ->
        if (error != null) Log.w(TAG, "$action falhou: ${error.description}") else Log.i(TAG, "$action: ok")
        if (onDone != null) main.post { onDone(error) }
    }
}
