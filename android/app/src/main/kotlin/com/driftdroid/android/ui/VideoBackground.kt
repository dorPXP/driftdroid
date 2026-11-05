package com.driftdroid.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/**
 * A muted, looping video behind the launcher, placed with the same upright/sideways framing as a
 * picture. It plays only while the launcher is on screen ([start] / [stop] follow the activity) and
 * only when [BackgroundMedia.mayAnimate] allows it; otherwise the first frame is drawn by the
 * background drawable underneath. A dim layer sits on top so text stays readable.
 */
internal class VideoBackground(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val dim = View(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var wanted = false
    private val placed = RectF()

    init {
        texture.surfaceTextureListener = this
        texture.isOpaque = false
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dim.setBackgroundColor(Lx.alpha(Color.BLACK, Theme.dim))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun start() {
        wanted = true
        if (!BackgroundMedia.mayAnimate(context)) return
        val s = texture.surfaceTexture
        if (s != null) begin(s)
    }

    fun stop() {
        wanted = false
        release()
    }

    private fun begin(st: SurfaceTexture) {
        if (player != null) {
            runCatching { player?.start() }
            return
        }
        val file = BackgroundMedia.videoFile(context)
        if (!file.isFile) return
        surface = Surface(st)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.path)
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                applyFraming()
            }
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                release()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            release()
        }
    }

    private fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }

    /** Scales and moves the video the way the picture framing says for the current screen shape. */
    fun applyFraming() {
        if (videoW <= 0 || videoH <= 0 || width <= 0 || height <= 0) return
        val framing = Theme.framingFor(width, height)
        Theme.place(videoW, videoH, width.toFloat(), height.toFloat(), framing, placed)
        // The texture is stretched over the whole view by default; scale it to the placed rectangle.
        val m = Matrix()
        m.setScale(placed.width() / width, placed.height() / height)
        m.postTranslate(placed.left, placed.top)
        texture.setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFraming()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (wanted && BackgroundMedia.mayAnimate(context)) begin(st)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
