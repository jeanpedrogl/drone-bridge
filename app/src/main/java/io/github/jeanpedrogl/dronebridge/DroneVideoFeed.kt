package io.github.jeanpedrogl.dronebridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager

/**
 * Decodes the drone's primary video feed (received over OcuSync via the DJI SDK) into
 * [textureView]. Purely for display/monitoring on both the phone and — via [currentFrameBitmap],
 * relayed over the ROS2 bridge — the computer; the aircraft is never controlled from this feed.
 */
class DroneVideoFeed(private val context: Context, private val textureView: TextureView) {

    private var codecManager: DJICodecManager? = null
    private var started = false

    private val videoDataListener =
        VideoFeeder.VideoDataListener { videoBuffer, size -> codecManager?.sendDataToDecoder(videoBuffer, size) }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (codecManager == null) {
                codecManager = DJICodecManager(context, surface, width, height)
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            codecManager?.cleanSurface()
            codecManager?.destroyCodec()
            codecManager = null
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    /** Starts decoding into the TextureView. Safe to call once the view is already laid out. */
    fun start() {
        if (started) return
        started = true
        textureView.surfaceTextureListener = surfaceTextureListener
        // The view may already have a surface (e.g. returning from background): the listener
        // above only fires on first creation, so replay it manually in that case.
        textureView.surfaceTexture?.let {
            surfaceTextureListener.onSurfaceTextureAvailable(it, textureView.width, textureView.height)
        }
        VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(videoDataListener)
    }

    fun stop() {
        // Never started (SDK not registered: permissions denied, bad App Key): VideoFeeder doesn't exist yet.
        if (started) VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(videoDataListener)
        started = false
        codecManager?.cleanSurface()
        codecManager?.destroyCodec()
        codecManager = null
    }

    /**
     * The most recently rendered frame scaled to at most [maxWidth] pixels wide, or null if the
     * surface isn't ready. The scaling happens while reading the texture, so the full-size frame
     * (the size of the phone screen) is never copied into memory.
     */
    fun currentFrameBitmap(maxWidth: Int): Bitmap? {
        if (!textureView.isAvailable || textureView.width == 0 || textureView.height == 0) return null
        val width = minOf(maxWidth, textureView.width)
        val height = (textureView.height.toLong() * width / textureView.width).toInt().coerceAtLeast(1)
        return textureView.getBitmap(width, height)
    }
}
