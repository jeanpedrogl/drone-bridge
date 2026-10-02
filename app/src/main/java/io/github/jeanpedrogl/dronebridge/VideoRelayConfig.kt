package io.github.jeanpedrogl.dronebridge

import org.json.JSONObject

/**
 * How the drone video is relayed to the computer: frame rate, maximum width and JPEG quality.
 * Changed at runtime by the computer (`video.set_quality`) so it can trade quality for speed in
 * flight. Main thread only. Defaults are the values the app has always used.
 */
class VideoRelayConfig {
    var fps = DEFAULT_FPS
        private set
    var maxWidth = DEFAULT_MAX_WIDTH
        private set
    var jpegQuality = DEFAULT_JPEG_QUALITY
        private set

    val periodMs: Long get() = 1000L / fps

    /** Applies the given values (null = keep) or throws without changing anything if one is out of range. */
    fun update(fps: Int?, maxWidth: Int?, jpegQuality: Int?) {
        require(fps == null || fps in FPS_RANGE) { "fps deve estar entre ${FPS_RANGE.first} e ${FPS_RANGE.last}" }
        require(maxWidth == null || maxWidth in WIDTH_RANGE) {
            "max_width deve estar entre ${WIDTH_RANGE.first} e ${WIDTH_RANGE.last}"
        }
        require(jpegQuality == null || jpegQuality in QUALITY_RANGE) {
            "jpeg_quality deve estar entre ${QUALITY_RANGE.first} e ${QUALITY_RANGE.last}"
        }
        fps?.let { this.fps = it }
        maxWidth?.let { this.maxWidth = it }
        jpegQuality?.let { this.jpegQuality = it }
    }

    fun toJson(): JSONObject =
        JSONObject().put("fps", fps).put("max_width", maxWidth).put("jpeg_quality", jpegQuality)

    companion object {
        const val DEFAULT_FPS = 8
        const val DEFAULT_MAX_WIDTH = 640
        const val DEFAULT_JPEG_QUALITY = 50
        val FPS_RANGE = 1..30
        val WIDTH_RANGE = 160..1920
        val QUALITY_RANGE = 10..95
    }
}
