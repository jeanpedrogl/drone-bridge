package io.github.jeanpedrogl.dronebridge

import android.os.Handler
import android.os.Looper
import dji.common.error.DJIError
import dji.common.flightcontroller.ConnectionFailSafeBehavior
import dji.common.flightcontroller.FlightMode
import dji.common.gimbal.GimbalMode
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.model.LocationCoordinate2D
import dji.common.camera.SettingsDefinitions.CameraMode
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera
import dji.sdk.flightcontroller.FlightController
import dji.sdk.gimbal.Gimbal
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

// If some getters of an aggregate command never call back (unsupported on this aircraft), answer
// with what arrived after this long. Must stay below RpcRegistry.DEFAULT_TIMEOUT_MS.
private const val GATHER_TIMEOUT_MS = 6_000L
private const val DEFAULT_STREAM_INTERVAL_MS = 1_000L

/**
 * The commands the computer can run on the aircraft through [RpcRegistry]: flight settings,
 * return to home, gimbal and camera. To expose something new, add a `cmd(...)` line in the right
 * `register*` function — nothing else in the app needs to change. Documented for the computer
 * side in `PROTOCOLO.md`.
 *
 * Deliberately NOT here: panic (phone-only), motors on/off, reboot, calibrations, camera settings
 * (ISO, shutter, ...), SD card formatting and missions.
 *
 * Several of these are not supported by every aircraft. Failures come back as the SDK's own error
 * text; `system.probe` shows which read-only ones this aircraft answers.
 */
class DroneCommands(
    private val registry: RpcRegistry,
    private val controller: DroneController,
    private val states: DeviceStates,
    private val streamer: StateStreamer,
    private val videoRelay: VideoRelayConfig,
) {
    private val main = Handler(Looper.getMainLooper())

    private val aircraft: Aircraft?
        get() = DJISDKManager.getInstance().product as? Aircraft

    fun registerAll() {
        registerSystem()
        registerState()
        registerVideo()
        registerFlight()
        registerHome()
        registerGimbal()
        registerCamera()
    }

    // ---------------------------------------------------------------- system

    private fun registerSystem() {
        cmd("system.info", "Modelo, firmware e quais componentes estão presentes.", probe = true) { _, r ->
            val product = DJISDKManager.getInstance().product
            val a = aircraft
            r.ok(
                JSONObject()
                    .put("connected", product?.isConnected ?: false)
                    .put("model", product?.model?.name ?: JSONObject.NULL)
                    .put("firmware", product?.firmwarePackageVersion ?: JSONObject.NULL)
                    .put("has_flight_controller", a?.flightController != null)
                    .put("has_gimbal", a?.gimbal != null)
                    .put("has_camera", a?.camera != null)
                    .put("has_battery", a?.battery != null)
                    .put("has_remote_controller", a?.remoteController != null)
            )
        }
    }

    // ---------------------------------------------------------------- state

    // Nothing is sent unless the computer asks: state.get for one snapshot, state.stream_start for
    // a periodic push on /phone/state.
    private fun registerState() {
        cmd(
            "state.get", "Um retrato do estado atual (voo, gimbal, câmera, bateria, controle remoto).",
            "sections:string[]? (${DeviceStates.SECTIONS.joinToString()}; omitido = todas)", probe = true,
        ) { a, r -> r.ok(states.toJson(sectionsArg(a))) }
        cmd(
            "state.stream_start", "Começa a publicar o estado em /phone/state. Desligado por padrão.",
            "interval_ms:int? (padrão 1000, mínimo 100), sections:string[]?",
        ) { a, r ->
            val interval = if (a.has("interval_ms")) a.getLong("interval_ms") else DEFAULT_STREAM_INTERVAL_MS
            streamer.start(interval, sectionsArg(a))
            r.ok()
        }
        cmd("state.stream_stop", "Para de publicar o estado em /phone/state.") { _, r ->
            streamer.stop()
            r.ok()
        }
    }

    /** `sections` argument as a validated set, or null for "all". */
    private fun sectionsArg(a: JSONObject): Set<String>? {
        val list = a.optJSONArray("sections") ?: return null
        val sections = (0 until list.length()).map { list.getString(it) }.toSet()
        val unknown = sections - DeviceStates.SECTIONS.toSet()
        require(unknown.isEmpty()) { "Seções desconhecidas: ${unknown.joinToString()}. Use: ${DeviceStates.SECTIONS.joinToString()}" }
        return sections
    }

    // ---------------------------------------------------------------- video

    // The video the computer receives is a re-encoded copy (JPEG over the bridge), not the
    // aircraft's own stream; these settings trade image quality against load on phone and Wi-Fi.
    private fun registerVideo() {
        cmd(
            "video.set_quality", "Ajusta o vídeo enviado ao computador. Omitido = mantém o valor atual.",
            "fps:int? (1-30), max_width:int? (160-1920), jpeg_quality:int? (10-95)",
        ) { a, r ->
            videoRelay.update(
                fps = if (a.has("fps")) a.getInt("fps") else null,
                maxWidth = if (a.has("max_width")) a.getInt("max_width") else null,
                jpegQuality = if (a.has("jpeg_quality")) a.getInt("jpeg_quality") else null,
            )
            r.ok(videoRelay.toJson())
        }
        cmd("video.get", "Lê os ajustes atuais do vídeo enviado ao computador.", probe = true) { _, r ->
            r.ok(videoRelay.toJson())
        }
    }

    // ---------------------------------------------------------------- flight

    private fun registerFlight() {
        cmd("flight.take_off", "Decola.") { _, r -> controller.takeOff { error -> finish(r, error) } }
        cmd("flight.land", "Pousa (e confirma o pouso quando o drone pede).") { _, r ->
            controller.land { error -> finish(r, error) }
        }
        cmd(
            "flight.cancel_land_or_rth",
            "Cancela um pouso ou um retorno para casa em andamento (não mexe na decolagem). Escolhe pelo " +
                "estado do voo e, se o estado não ajudar, tenta os dois. Falha se não houver nada para cancelar.",
        ) { _, r ->
            val fc = aircraft?.flightController ?: return@cmd r.fail(NO_FLIGHT_CONTROLLER)
            cancelLandingOrRth(fc, r)
        }
        flightCmd("flight.cancel_takeoff", "Cancela uma decolagem em andamento.") { fc, _, cb -> fc.cancelTakeoff(cb) }
        flightCmd("flight.cancel_landing", "Cancela um pouso em andamento.") { fc, _, cb -> fc.cancelLanding(cb) }

        cmd(
            "flight.settings", "Lê os parâmetros de voo: altura/raio máximos, limiares de bateria, falha de sinal, RTH.",
            probe = true,
        ) { _, r ->
            val fc = aircraft?.flightController ?: return@cmd r.fail(NO_FLIGHT_CONTROLLER)
            gather(r, listOf(
                "home_return_height_m" to { s -> fc.getGoHomeHeightInMeters(s.cb()) },
                "max_height_m" to { s -> fc.getMaxFlightHeight(s.cb()) },
                "max_radius_m" to { s -> fc.getMaxFlightRadius(s.cb()) },
                "radius_limit_enabled" to { s -> fc.getMaxFlightRadiusLimitationEnabled(s.cb()) },
                "low_battery_threshold_percent" to { s -> fc.getLowBatteryWarningThreshold(s.cb()) },
                "serious_low_battery_threshold_percent" to { s -> fc.getSeriousLowBatteryWarningThreshold(s.cb()) },
                "failsafe_behavior" to { s -> fc.getConnectionFailSafeBehavior(s.cb()) },
                "smart_rth_enabled" to { s -> fc.getSmartReturnToHomeEnabled(s.cb()) },
                "novice_mode" to { s -> fc.getNoviceModeEnabled(s.cb()) },
            ))
        }
        flightCmd("flight.set_max_height", "Altura máxima de voo.", "meters:int") { fc, a, cb ->
            fc.setMaxFlightHeight(a.intArg("meters"), cb)
        }
        flightCmd("flight.set_max_radius", "Raio máximo de voo a partir de casa.", "meters:int") { fc, a, cb ->
            fc.setMaxFlightRadius(a.intArg("meters"), cb)
        }
        flightCmd("flight.set_radius_limit", "Liga/desliga o limite de raio.", "enabled:bool") { fc, a, cb ->
            fc.setMaxFlightRadiusLimitationEnabled(a.boolArg("enabled"), cb)
        }
        flightCmd("flight.set_low_battery_threshold", "Aviso de bateria baixa.", "percent:int") { fc, a, cb ->
            fc.setLowBatteryWarningThreshold(a.intArg("percent"), cb)
        }
        flightCmd(
            "flight.set_serious_low_battery_threshold", "Aviso de bateria crítica (o drone age sozinho).", "percent:int",
        ) { fc, a, cb -> fc.setSeriousLowBatteryWarningThreshold(a.intArg("percent"), cb) }
        flightCmd(
            "flight.set_failsafe", "O que o drone faz ao perder o sinal do controle: HOVER | LANDING | GO_HOME.",
            "behavior:string", confirm = true,
        ) { fc, a, cb ->
            val allowed = enumValues<ConnectionFailSafeBehavior>().filter { it != ConnectionFailSafeBehavior.UNKNOWN }
            fc.setConnectionFailSafeBehavior(a.enumArg("behavior", allowed), cb)
        }
    }

    /**
     * Each of the SDK's cancel calls fails when its own operation isn't running, so a single one
     * can't serve as "cancel the landing or the return to home, whichever is happening". Tries the
     * one the flight state points to first, then the other, and answers with the first success.
     * Takeoff is deliberately not included: a "cancel" action on the computer must never abort a takeoff.
     */
    private fun cancelLandingOrRth(fc: FlightController, r: RpcReply) {
        val state = states.flight
        val mode = state?.flightMode
        val landing = state?.isLandingConfirmationNeeded == true || mode == FlightMode.AUTO_LANDING ||
            mode == FlightMode.ATTI_LANDING || mode == FlightMode.CONFIRM_LANDING
        val goingHome = state?.isGoingHome == true || mode == FlightMode.GO_HOME
        val attempts = listOf<Pair<Boolean, (CommonCallbacks.CompletionCallback<DJIError>) -> Unit>>(
            landing to { cb -> fc.cancelLanding(cb) },
            goingHome to { cb -> fc.cancelGoHome(cb) },
        ).sortedByDescending { it.first }.map { it.second }

        fun attempt(index: Int, firstError: String?) {
            if (index == attempts.size) {
                r.fail("Nada para cancelar: o drone não está pousando nem voltando para casa. " +
                    "(${firstError ?: "sem detalhe do SDK"})")
                return
            }
            attempts[index](CommonCallbacks.CompletionCallback<DJIError> { error ->
                if (error == null) r.ok() else attempt(index + 1, firstError ?: error.description)
            })
        }
        attempt(0, null)
    }

    // ---------------------------------------------------------------- return to home

    private fun registerHome() {
        flightCmd("home.go_home", "Inicia o retorno para casa.") { fc, _, cb -> fc.startGoHome(cb) }
        flightCmd("home.cancel_go_home", "Cancela o retorno para casa.") { fc, _, cb -> fc.cancelGoHome(cb) }
        flightCmd("home.set_here", "Define a posição atual do drone como casa.") { fc, _, cb ->
            fc.setHomeLocationUsingAircraftCurrentLocation(cb)
        }
        flightCmd("home.set_location", "Define a casa em coordenadas.", "latitude:double, longitude:double") { fc, a, cb ->
            val lat = a.doubleArg("latitude")
            val lon = a.doubleArg("longitude")
            require(LocationCoordinate2D.isValid(lat, lon)) { "Coordenadas inválidas: $lat, $lon" }
            fc.setHomeLocation(LocationCoordinate2D(lat, lon), cb)
        }
        flightCmd("home.set_return_height", "Altura em que o drone sobe antes de voltar.", "meters:int") { fc, a, cb ->
            fc.setGoHomeHeightInMeters(a.intArg("meters"), cb)
        }
        flightCmd("home.set_smart_rth", "Liga/desliga o retorno inteligente (por bateria).", "enabled:bool") { fc, a, cb ->
            fc.setSmartReturnToHomeEnabled(a.boolArg("enabled"), cb)
        }
        cmd("home.get", "Lê a posição de casa registrada no drone.", probe = true) { _, r ->
            val fc = aircraft?.flightController ?: return@cmd r.fail(NO_FLIGHT_CONTROLLER)
            fc.getHomeLocation(valueCb(r))
        }
    }

    // ---------------------------------------------------------------- gimbal

    // Mini SE: only pitch (tilt) is controllable, -90..0 degrees, or up to +20 with the range
    // extension. Roll and yaw are stabilized automatically and ignore commands. The SDK does not
    // reject such commands up front; gimbal.capabilities reports what the connected gimbal accepts.
    private fun registerGimbal() {
        gimbalCmd(
            "gimbal.rotate",
            "Gira o gimbal. mode: SPEED (graus/s) | RELATIVE_ANGLE | ABSOLUTE_ANGLE. Eixos omitidos ficam parados. " +
                "No Mini SE só pitch tem efeito.",
            "mode:string, pitch:float?, roll:float?, yaw:float?, time:double? (segundos, só nos modos de ângulo)",
        ) { g, a, cb ->
            val mode = a.enumArg<RotationMode>("mode")
            val unused = if (mode == RotationMode.SPEED) 0f else Rotation.NO_ROTATION
            val builder = Rotation.Builder()
                .mode(mode)
                .pitch(a.floatOr("pitch", unused))
                .roll(a.floatOr("roll", unused))
                .yaw(a.floatOr("yaw", unused))
            if (a.has("time")) builder.time(a.getDouble("time"))
            g.rotate(builder.build(), cb)
        }
        gimbalCmd("gimbal.reset", "Volta o gimbal à posição inicial.") { g, _, cb -> g.reset(cb) }
        gimbalCmd("gimbal.set_mode", "Modo do gimbal: FREE | FPV | YAW_FOLLOW.", "mode:string") { g, a, cb ->
            val allowed = enumValues<GimbalMode>().filter { it != GimbalMode.UNKNOWN }
            g.setMode(a.enumArg("mode", allowed), cb)
        }
        gimbalCmd(
            "gimbal.set_pitch_range_extension", "Estende a inclinação para cima (Mini SE: de 0° para +20°).",
            "enabled:bool",
        ) { g, a, cb -> g.setPitchRangeExtensionEnabled(a.boolArg("enabled"), cb) }
        cmd("gimbal.get_pitch_range_extension", "Lê se a extensão de inclinação está ligada.", probe = true) { _, r ->
            val g = aircraft?.gimbal ?: return@cmd r.fail(NO_GIMBAL)
            g.getPitchRangeExtensionEnabled(valueCb(r))
        }
        cmd("gimbal.capabilities", "Quais ajustes este gimbal aceita (por exemplo ADJUST_PITCH, ADJUST_YAW).", probe = true) { _, r ->
            val g = aircraft?.gimbal ?: return@cmd r.fail(NO_GIMBAL)
            val out = JSONObject()
            g.capabilities?.forEach { (key, capability) -> out.put(key.name, capability.isSupported) }
            r.ok(out)
        }
    }

    // ---------------------------------------------------------------- camera

    private fun registerCamera() {
        cameraCmd("camera.start_photo", "Tira uma foto (o modo da câmera precisa ser SHOOT_PHOTO).") { c, _, cb ->
            c.startShootPhoto(cb)
        }
        cameraCmd("camera.stop_photo", "Interrompe a foto (necessário nos modos intervalo/timelapse).") { c, _, cb ->
            c.stopShootPhoto(cb)
        }
        cameraCmd("camera.start_record", "Começa a gravar (o modo da câmera precisa ser RECORD_VIDEO).") { c, _, cb ->
            c.startRecordVideo(cb)
        }
        cameraCmd("camera.stop_record", "Para a gravação.") { c, _, cb -> c.stopRecordVideo(cb) }
        cameraCmd("camera.set_mode", "Modo da câmera: SHOOT_PHOTO | RECORD_VIDEO.", "mode:string") { c, a, cb ->
            c.setMode(a.enumArg("mode", listOf(CameraMode.SHOOT_PHOTO, CameraMode.RECORD_VIDEO)), cb)
        }
        cmd("camera.get_mode", "Lê o modo atual da câmera.", probe = true) { _, r ->
            val c = aircraft?.camera ?: return@cmd r.fail(NO_CAMERA)
            c.getMode(valueCb(r))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun cmd(
        name: String,
        description: String,
        args: String = "",
        confirm: Boolean = false,
        probe: Boolean = false,
        run: (JSONObject, RpcReply) -> Unit,
    ) = registry.register(RpcCommand(name, description, args, confirm, probe, run))

    private fun flightCmd(
        name: String, description: String, args: String = "", confirm: Boolean = false,
        call: (FlightController, JSONObject, CommonCallbacks.CompletionCallback<DJIError>) -> Unit,
    ) = cmd(name, description, args, confirm) { a, r ->
        val fc = aircraft?.flightController ?: return@cmd r.fail(NO_FLIGHT_CONTROLLER)
        call(fc, a, ack(r))
    }

    private fun gimbalCmd(
        name: String, description: String, args: String = "",
        call: (Gimbal, JSONObject, CommonCallbacks.CompletionCallback<DJIError>) -> Unit,
    ) = cmd(name, description, args) { a, r ->
        val g = aircraft?.gimbal ?: return@cmd r.fail(NO_GIMBAL)
        call(g, a, ack(r))
    }

    private fun cameraCmd(
        name: String, description: String, args: String = "",
        call: (Camera, JSONObject, CommonCallbacks.CompletionCallback<DJIError>) -> Unit,
    ) = cmd(name, description, args) { a, r ->
        val c = aircraft?.camera ?: return@cmd r.fail(NO_CAMERA)
        call(c, a, ack(r))
    }

    private fun finish(r: RpcReply, error: String?) = if (error == null) r.ok() else r.fail(error)

    /** SDK completion callback that answers the command: ok without a value, or the SDK's error. */
    private fun ack(r: RpcReply) = CommonCallbacks.CompletionCallback<DJIError> { error ->
        if (error == null) r.ok() else r.fail(error.description)
    }

    /** SDK getter callback that answers the command with the value read. */
    private fun <T> valueCb(r: RpcReply) = object : CommonCallbacks.CompletionCallbackWith<T> {
        override fun onSuccess(value: T) = r.ok(plain(value))
        override fun onFailure(error: DJIError?) = r.fail(error?.description ?: "Erro desconhecido")
    }

    /** Collects one getter's outcome into an aggregate answer; see [gather]. */
    private class Sink(val ok: (Any?) -> Unit, val fail: (String) -> Unit) {
        fun <T> cb() = object : CommonCallbacks.CompletionCallbackWith<T> {
            override fun onSuccess(value: T) = ok(plain(value))
            override fun onFailure(error: DJIError?) = fail(error?.description ?: "Erro desconhecido")
        }
    }

    /** Runs all [getters] and answers `{name: {ok, value | error}}` once each has reported. */
    private fun gather(reply: RpcReply, getters: List<Pair<String, (Sink) -> Unit>>) {
        val out = JSONObject()
        val pending = AtomicInteger(getters.size)
        fun record(name: String, ok: Boolean, value: Any?) {
            synchronized(out) {
                if (out.has(name)) return
                out.put(name, JSONObject().put("ok", ok).put(if (ok) "value" else "error", value ?: JSONObject.NULL))
            }
            if (pending.decrementAndGet() == 0) reply.ok(out)
        }
        main.postDelayed({
            synchronized(out) {
                getters.filter { !out.has(it.first) }.forEach {
                    out.put(it.first, JSONObject().put("ok", false).put("error", "Sem resposta (provavelmente não suportado)"))
                }
            }
            reply.ok(out)
        }, GATHER_TIMEOUT_MS)
        getters.forEach { (name, call) ->
            val sink = Sink({ record(name, true, it) }, { record(name, false, it) })
            try {
                call(sink)
            } catch (e: Exception) {
                sink.fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val NO_FLIGHT_CONTROLLER = "Controle de voo indisponível (drone não conectado?)."
        const val NO_GIMBAL = "Gimbal indisponível (drone não conectado?)."
        const val NO_CAMERA = "Câmera indisponível (drone não conectado?)."
    }
}

/** Turns SDK values into JSON-friendly ones: enums by name, coordinates as an object. */
private fun plain(value: Any?): Any? = when (value) {
    is Enum<*> -> value.name
    is LocationCoordinate2D ->
        JSONObject().put("latitude", value.latitude).put("longitude", value.longitude).put("valid", value.isValid)
    else -> value
}

private fun JSONObject.intArg(key: String): Int = requireKey(key).getInt(key)
private fun JSONObject.doubleArg(key: String): Double = requireKey(key).getDouble(key)
private fun JSONObject.boolArg(key: String): Boolean = requireKey(key).getBoolean(key)
private fun JSONObject.floatOr(key: String, default: Float): Float =
    if (has(key)) getDouble(key).toFloat() else default

private fun JSONObject.requireKey(key: String): JSONObject {
    require(has(key)) { "Falta o argumento '$key'." }
    return this
}

private inline fun <reified E : Enum<E>> JSONObject.enumArg(
    key: String,
    allowed: List<E> = enumValues<E>().toList(),
): E {
    requireKey(key)
    val raw = optString(key)
    return allowed.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw IllegalArgumentException("'$key' inválido: '$raw'. Use um de: ${allowed.joinToString { it.name }}")
}
