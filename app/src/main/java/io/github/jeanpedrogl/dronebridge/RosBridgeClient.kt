package io.github.jeanpedrogl.dronebridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val TAG = "RosBridgeClient"
private const val RECONNECT_DELAY_MS = 3000L

/**
 * Minimal rosbridge v2.0 (JSON-over-WebSocket) client: just enough `subscribe`/`advertise`/
 * `publish` to talk to the `rosbridge_websocket` server running on the computer.
 *
 * There is no ROS2 client library for Android (no `rclpy`/`rclcpp` here) — this is the standard
 * substitute the ROS2 community itself uses for mobile/web clients. From the computer's point of
 * view this phone is just another ROS2 node; rosbridge does the JSON<->ROS2 message translation.
 * The topic contract is `PROTOCOLO.md` at the repository root.
 *
 * All callbacks to [listener] and to `onMessage` lambdas passed to [subscribe] happen on the main
 * thread, even though OkHttp delivers them on its own thread internally.
 */
class RosBridgeClient(private val listener: Listener) {

    interface Listener {
        fun onConnectionChanged(connected: Boolean)
    }

    private data class Subscription(val type: String, val onMessage: (JSONObject) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var desiredRequest: Request? = null
    private var connected = false
    private val subscriptions = mutableMapOf<String, Subscription>()
    private val advertised = mutableSetOf<String>()

    private val reconnectRunnable = Runnable {
        desiredRequest?.let {
            webSocket?.cancel()   // the dead socket, if OkHttp still holds it
            openSocket(it)
        }
    }

    /**
     * (Re)connects to `ws://host:port` and re-subscribes everything registered via [subscribe].
     * Returns false, changing nothing, if [url] is not a valid WebSocket address.
     */
    fun connect(url: String): Boolean {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Endereço inválido: $url")
            return false
        }
        desiredRequest = request
        main.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "reconnecting")
        // The old connection is gone as far as publishing is concerned; the new one re-advertises on open.
        setConnected(false)
        openSocket(request)
        return true
    }

    fun disconnect() {
        desiredRequest = null
        main.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "bye")
        webSocket = null
        setConnected(false)
    }

    /** Registers interest in `topic`; survives reconnects. Call once per topic, it's idempotent. */
    fun subscribe(topic: String, type: String, onMessage: (JSONObject) -> Unit) {
        subscriptions[topic] = Subscription(type, onMessage)
        if (connected) sendSubscribe(topic, type)
    }

    /**
     * Bytes accepted by [publish] that the network has not taken yet. A growing value means the
     * link is slower than what is being sent; the video relay drops frames instead of queueing them.
     */
    fun pendingBytes(): Long = webSocket?.queueSize() ?: 0L

    /** Publishes `msg` on `topic`, advertising it first the first time it's used after a connect. */
    fun publish(topic: String, type: String, msg: JSONObject) {
        if (!connected) return
        if (advertised.add(topic)) sendAdvertise(topic, type)
        send(JSONObject().put("op", "publish").put("topic", topic).put("msg", msg))
    }

    private fun openSocket(request: Request) {
        // Events from a socket that is no longer the current one (closed by connect() with a new
        // address, say) are ignored: otherwise its late "closed" would mark the live socket as down
        // and schedule a second connection, and the computer's messages would be handled twice.
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (this@RosBridgeClient.webSocket !== webSocket) return@post
                    setConnected(true)
                    advertised.clear()
                    subscriptions.forEach { (topic, sub) -> sendSubscribe(topic, sub.type) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (this@RosBridgeClient.webSocket === webSocket) handleIncoming(text) }
            }

            // The computer closed the connection (rosbridge restarted, say). OkHttp only calls
            // onClosed once we close our side too; without this the phone never reconnects.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Conexão com a ponte falhou: ${t.message}")
                main.post { if (this@RosBridgeClient.webSocket === webSocket) onSocketClosed() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (this@RosBridgeClient.webSocket === webSocket) onSocketClosed() }
            }
        })
    }

    private fun onSocketClosed() {
        setConnected(false)
        // At most one pending reconnect, even if a socket reports both a failure and a close.
        main.removeCallbacks(reconnectRunnable)
        if (desiredRequest != null) main.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        listener.onConnectionChanged(value)
    }

    private fun handleIncoming(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Mensagem inválida da ponte: $text")
            return
        }
        if (json.optString("op") != "publish") return
        val topic = json.optString("topic")
        val msg = json.optJSONObject("msg") ?: JSONObject()
        subscriptions[topic]?.onMessage?.invoke(msg)
    }

    private fun sendSubscribe(topic: String, type: String) {
        send(JSONObject().put("op", "subscribe").put("topic", topic).put("type", type))
    }

    private fun sendAdvertise(topic: String, type: String) {
        send(JSONObject().put("op", "advertise").put("topic", topic).put("type", type))
    }

    private fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }
}
