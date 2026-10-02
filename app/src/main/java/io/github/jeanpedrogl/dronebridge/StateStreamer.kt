package io.github.jeanpedrogl.dronebridge

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

private const val MIN_INTERVAL_MS = 100L

/**
 * Optional periodic push of [DeviceStates] to the computer, off until the computer asks for it
 * (`state.stream_start`). [publish] delivers one snapshot to the bridge. Main thread only.
 */
class StateStreamer(private val states: DeviceStates, private val publish: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var intervalMs = 0L
    private var sections: Set<String>? = null

    val isRunning: Boolean get() = intervalMs > 0

    private val loop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            publish(states.toJson(sections))
            main.postDelayed(this, intervalMs)
        }
    }

    /** Starts (or reconfigures) the stream; [intervalMs] is clamped to at least 100 ms. */
    fun start(intervalMs: Long, sections: Set<String>?) {
        main.removeCallbacks(loop)
        this.intervalMs = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        this.sections = sections
        main.post(loop)
    }

    fun stop() {
        intervalMs = 0
        main.removeCallbacks(loop)
    }
}
