package io.github.jeanpedrogl.dronebridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

private const val TAG = "DroneApp"

private const val PREFS_NAME = "ponte_computador"
private const val PREF_ADDRESS = "endereco"
private const val DEFAULT_COMPUTER_ADDRESS = "10.42.0.1:9090"
private const val DEFAULT_ROSBRIDGE_PORT = 9090

private const val TELEMETRY_PUBLISH_PERIOD_MS = 500L

// Vídeo retransmitido ao computador: se o Wi-Fi já tem mais que isso na fila, o quadro é descartado
// em vez de enfileirado (fila = atraso crescente na imagem).
private const val VIDEO_MAX_BACKLOG_BYTES = 256 * 1024L

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.BLUETOOTH,
    Manifest.permission.BLUETOOTH_ADMIN,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.RECORD_AUDIO,
)

class MainActivity : AppCompatActivity(), DroneController.Listener, RosBridgeClient.Listener {

    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var telemetryText: TextView
    private lateinit var droneVideoView: TextureView
    private lateinit var assumeControlButton: MaterialButton
    private lateinit var takeoffButton: MaterialButton
    private lateinit var landButton: MaterialButton
    private lateinit var panicButton: MaterialButton

    private val states = DeviceStates()
    private val controller = DroneController(this, states)
    private val rosBridge = RosBridgeClient(this)
    private val stateStreamer = StateStreamer(states) { snapshot ->
        rosBridge.publish("/phone/state", "std_msgs/String", JSONObject().put("data", snapshot.toString()))
    }
    private val videoRelay = VideoRelayConfig()
    // JPEG + base64 of each relayed frame run here, not on the main thread, which also drives the
    // 50 ms virtual stick loop. At most one frame is in flight (videoBusy); extra ticks skip.
    private val videoEncoder = Executors.newSingleThreadExecutor()
    private val videoBusy = AtomicBoolean(false)
    private val rpc = RpcRegistry(schedule = { delayMs, task -> main.postDelayed(task, delayMs) })
    private lateinit var droneVideoFeed: DroneVideoFeed
    private val main = Handler(Looper.getMainLooper())

    private var droneConnected = false
    private var bridgeConnected = false
    private var stickMode = false
    private var lastTelemetry: Telemetry? = null
    private var batteryPercent: Int? = null

    private val requestPermissionsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                registerWithDji()
            } else {
                statusText.text = "Permissões necessárias não concedidas."
            }
        }

    private val videoRelayLoop = object : Runnable {
        override fun run() {
            relayVideoFrame()
            main.postDelayed(this, videoRelay.periodMs)
        }
    }

    private val telemetryPublishLoop = object : Runnable {
        override fun run() {
            publishTelemetry()
            main.postDelayed(this, TELEMETRY_PUBLISH_PERIOD_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setUpWindowInsets()

        statusText = findViewById(R.id.statusText)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        telemetryText = findViewById(R.id.telemetryText)
        droneVideoView = findViewById(R.id.droneVideoView)
        assumeControlButton = findViewById(R.id.assumeControlButton)
        takeoffButton = findViewById(R.id.takeoffButton)
        landButton = findViewById(R.id.landButton)
        panicButton = findViewById(R.id.panicButton)

        droneVideoFeed = DroneVideoFeed(this, droneVideoView)
        DroneCommands(rpc, controller, states, stateStreamer, videoRelay).registerAll()

        assumeControlButton.setOnClickListener { controller.setStickMode(false) }
        takeoffButton.setOnClickListener { controller.takeOff() }
        landButton.setOnClickListener { controller.land() }
        panicButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            controller.panic()
        }
        connectionStatusText.setOnClickListener { showComputerAddressDialog() }

        updateUi()
        renderConnectionStatus()
        notifyIfUsbAttached(intent)

        subscribeToCommandTopics()   // registered once; the client re-subscribes on every (re)connect
        connectToComputer(computerAddress())
        main.post(videoRelayLoop)
        main.post(telemetryPublishLoop)

        if (hasAllPermissions()) {
            registerWithDji()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    // launchMode=singleTop: plugging the remote while the app is open lands here, not in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notifyIfUsbAttached(intent)
    }

    // The SDK doesn't see the USB accessory attach on its own; it must be told.
    private fun notifyIfUsbAttached(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            sendBroadcast(Intent(DJISDKManager.USB_ACCESSORY_ATTACHED))
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onAppResumed()
    }

    override fun onPause() {
        super.onPause()
        // Touch input stops when we lose the foreground, so stop steering.
        controller.onAppPaused()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(videoRelayLoop)
        main.removeCallbacks(telemetryPublishLoop)
        videoEncoder.shutdown()
        droneVideoFeed.stop()
        rosBridge.disconnect()
    }

    /**
     * Fullscreen landscape UI. Runs again on every layout pass, so it also re-applies when the
     * phone is flipped between the two landscape orientations (volume buttons up / down): the
     * notch and system bar insets move to the other side and the padding follows them.
     */
    private fun setUpWindowInsets() {
        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun updateUi() {
        val flying = lastTelemetry?.isFlying
        val needsLandingConfirmation = lastTelemetry?.landingConfirmationNeeded == true

        assumeControlButton.isEnabled = stickMode
        // Unknown flight state (no telemetry yet) leaves both enabled; the SDK rejects invalid ones.
        takeoffButton.isEnabled = droneConnected && flying != true
        landButton.isEnabled = droneConnected && flying != false
        landButton.setText(if (needsLandingConfirmation) R.string.land_confirm else R.string.land)
    }

    override fun onMessage(message: String) {
        statusText.text = message
    }

    override fun onTelemetry(telemetry: Telemetry) {
        lastTelemetry = telemetry
        renderTelemetry()
        updateUi()
    }

    override fun onBattery(percent: Int) {
        batteryPercent = percent
        renderTelemetry()
    }

    override fun onStickModeChanged(enabled: Boolean) {
        stickMode = enabled
        updateUi()
    }

    override fun onConnectionChanged(connected: Boolean) {
        bridgeConnected = connected
        // A reconnecting computer starts from a clean slate: it must ask for the stream again.
        if (!connected) stateStreamer.stop()
        renderConnectionStatus()
    }

    private fun renderTelemetry() {
        val t = lastTelemetry
        val battery = batteryPercent?.let { "$it%" } ?: "—"
        telemetryText.text = if (t == null) {
            "Alt — · Vel — · GPS — · Bateria $battery"
        } else {
            String.format(
                Locale.getDefault(),
                "Alt %.1f m · Vel %.1f m/s · GPS %d sat · Bateria %s",
                t.altitudeM, t.speedMs, t.satellites, battery
            )
        }
    }

    private fun renderConnectionStatus() {
        val drone = getString(if (droneConnected) R.string.status_connected else R.string.status_disconnected)
        val computador = getString(if (bridgeConnected) R.string.status_connected else R.string.status_disconnected)
        connectionStatusText.text = getString(R.string.connection_status_format, drone, computador)
    }

    private fun registerWithDji() {
        statusText.text = "Registrando App Key com a DJI..."
        DJISDKManager.getInstance().registerApp(applicationContext, object : DJISDKManager.SDKManagerCallback {

            override fun onRegister(djiError: DJIError?) {
                if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                    Log.i(TAG, "DJI SDK registrado com sucesso")
                    runOnUiThread {
                        statusText.text = "SDK registrado. Conectando ao drone/controle..."
                        // VideoFeeder só existe depois que o SDK termina de registrar — chamar
                        // antes disso (ex.: direto no onCreate) derruba o app com NPE.
                        droneVideoFeed.start()
                    }
                    DJISDKManager.getInstance().startConnectionToProduct()
                } else {
                    Log.e(TAG, "Falha ao registrar o SDK: ${djiError?.description}")
                    runOnUiThread {
                        statusText.text = "Falha ao registrar: ${djiError?.description}\n" +
                            "Confira se o App Key no secrets.properties está correto e se há internet."
                    }
                }
            }

            override fun onProductDisconnect() {
                runOnUiThread {
                    droneConnected = false
                    lastTelemetry = null
                    batteryPercent = null   // otherwise the telemetry keeps reporting the old charge
                    controller.detach()
                    statusText.text = "Drone desconectado."
                    renderTelemetry()
                    renderConnectionStatus()
                    updateUi()
                }
            }

            override fun onProductConnect(baseProduct: BaseProduct?) {
                runOnUiThread {
                    droneConnected = true
                    statusText.text = "Conectado: ${baseProduct?.model}"
                    controller.attach()
                    renderConnectionStatus()
                    updateUi()
                }
            }

            override fun onProductChanged(baseProduct: BaseProduct?) {
                runOnUiThread { controller.attach() }
            }

            override fun onComponentChange(
                key: BaseProduct.ComponentKey?,
                oldComponent: BaseComponent?,
                newComponent: BaseComponent?
            ) {
                // The flight controller/battery can show up after the product itself.
                if (newComponent != null) runOnUiThread { controller.attach() }
            }

            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {}

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }

    // --- Ponte ROS2 (rosbridge) com o computador ---------------------------------------------

    private fun computerAddress(): String =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ADDRESS, DEFAULT_COMPUTER_ADDRESS) ?: DEFAULT_COMPUTER_ADDRESS

    private fun saveComputerAddress(address: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ADDRESS, address)
            .apply()
    }

    private fun showComputerAddressDialog() {
        val input = EditText(this).apply { setText(computerAddress()) }
        AlertDialog.Builder(this)
            .setTitle(R.string.computer_settings_title)
            .setMessage(R.string.computer_settings_hint)
            .setView(input)
            .setPositiveButton(R.string.computer_settings_positive) { _, _ ->
                val address = input.text.toString().trim()
                if (connectToComputer(address)) saveComputerAddress(address)
            }
            .setNegativeButton(R.string.computer_settings_negative, null)
            .show()
    }

    /**
     * Connects to `IP:porta` (a leading `ws://` or trailing `/` typed by the user is tolerated, and
     * a missing port means rosbridge's 9090 — without it OkHttp would silently try port 80).
     * An invalid address is reported instead of crashing: OkHttp throws on it, and a bad saved
     * address used to crash the app on every launch.
     */
    private fun connectToComputer(address: String): Boolean {
        val typed = address.trim().removePrefix("ws://").trimEnd('/')
        val hostPort = if (typed.isEmpty() || ':' in typed) typed else "$typed:$DEFAULT_ROSBRIDGE_PORT"
        if (hostPort.isNotEmpty() && rosBridge.connect("ws://$hostPort")) return true
        Toast.makeText(this, "Endereço do computador inválido: '$address'", Toast.LENGTH_LONG).show()
        return false
    }

    private fun subscribeToCommandTopics() {
        rosBridge.subscribe("/drone/cmd_vel", "geometry_msgs/Twist") { msg ->
            val linear = msg.optJSONObject("linear")
            val angular = msg.optJSONObject("angular")
            val forward = linear?.optDouble("x", 0.0) ?: 0.0
            // Convenção ROS (REP 103): y positivo = esquerda. O SDK da DJI espera "direita" positivo.
            val right = -(linear?.optDouble("y", 0.0) ?: 0.0)
            val up = linear?.optDouble("z", 0.0) ?: 0.0
            // Convenção ROS: yaw positivo = anti-horário (esquerda). Assumindo que a DJI usa o
            // sentido horário como positivo (não verificado em voo real — inverta aqui se o drone
            // girar para o lado errado).
            val yawRate = -(angular?.optDouble("z", 0.0) ?: 0.0)
            controller.setVelocityCommand(forward.toFloat(), right.toFloat(), up.toFloat(), yawRate.toFloat())
        }
        rosBridge.subscribe("/drone/gimbal_pitch_rate", "std_msgs/Float32") { msg ->
            controller.setGimbalRate(msg.optDouble("data", 0.0).toFloat())
        }
        rosBridge.subscribe("/drone/rpc/request", "std_msgs/String") { msg ->
            rpc.handle(msg.optString("data")) { response ->
                rosBridge.publish("/phone/rpc/response", "std_msgs/String", JSONObject().put("data", response.toString()))
            }
        }
        rosBridge.subscribe("/drone/takeoff", "std_msgs/Empty") { controller.takeOff() }
        rosBridge.subscribe("/drone/land", "std_msgs/Empty") { controller.land() }
        rosBridge.subscribe("/drone/stick_mode", "std_msgs/Bool") { msg ->
            controller.setStickMode(msg.optBoolean("data", false))
        }
    }

    private fun publishTelemetry() {
        val t = lastTelemetry
        val payload = JSONObject().apply {
            put("drone_conectado", droneConnected)
            put("stick_mode", stickMode)
            put("bateria_percent", batteryPercent ?: JSONObject.NULL)
            put("altitude_m", t?.altitudeM ?: JSONObject.NULL)
            put("velocidade_ms", t?.speedMs ?: JSONObject.NULL)
            put("satelites", t?.satellites ?: JSONObject.NULL)
            put("voando", t?.isFlying ?: JSONObject.NULL)
        }
        rosBridge.publish(
            "/phone/telemetria", "std_msgs/String",
            JSONObject().put("data", payload.toString())
        )
    }

    private fun relayVideoFrame() {
        if (!bridgeConnected || rosBridge.pendingBytes() > VIDEO_MAX_BACKLOG_BYTES) return
        if (!videoBusy.compareAndSet(false, true)) return
        val frame = droneVideoFeed.currentFrameBitmap(videoRelay.maxWidth)
        if (frame == null) {
            videoBusy.set(false)
            return
        }
        val quality = videoRelay.jpegQuality
        val capturedAtMs = System.currentTimeMillis()
        videoEncoder.execute {
            try {
                val jpeg = ByteArrayOutputStream().use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                val data = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                main.post { publishVideoFrame(data, capturedAtMs) }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao codificar quadro de vídeo", e)
            } finally {
                frame.recycle()
                videoBusy.set(false)
            }
        }
    }

    private fun publishVideoFrame(base64Jpeg: String, capturedAtMs: Long) {
        val header = JSONObject().apply {
            put("stamp", JSONObject().apply {
                put("sec", capturedAtMs / 1000)
                put("nanosec", (capturedAtMs % 1000) * 1_000_000)
            })
            put("frame_id", "celular")
        }
        val msg = JSONObject().apply {
            put("header", header)
            put("format", "jpeg")
            put("data", base64Jpeg)
        }
        rosBridge.publish("/phone/drone_camera/compressed", "sensor_msgs/CompressedImage", msg)
    }
}
