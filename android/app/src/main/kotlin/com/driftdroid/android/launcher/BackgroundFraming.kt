package com.driftdroid.android.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.T
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.Theme
import kotlin.math.max
import kotlin.math.min

/**
 * Where the background picture sits, separately for the launcher held upright and held sideways.
 * Drag to move the picture, pinch to zoom, or use Fill (cover the screen), Fit (show all of it) and
 * Centre. The frame is the real shape of this phone's screen in each direction.
 */
internal object BackgroundFraming {
    fun show(host: ModePickerActivity) {
        val image = Theme.image(host) ?: return
        val originalPortrait = Theme.portrait.copy()
        val originalLandscape = Theme.landscape.copy()
        val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val metrics = host.resources.displayMetrics
        val long = max(metrics.widthPixels, metrics.heightPixels).toFloat()
        val short = min(metrics.widthPixels, metrics.heightPixels).toFloat()

        val root = Lx.vbox(host)
        root.setBackgroundColor(0xFF0A0B0D.toInt())
        val view = FramingView(host, image, long, short)
        val tabs = Lx.hbox(host, Gravity.CENTER)
        var landscape = false
        lateinit var vertical: LinearLayout
        lateinit var horizontal: LinearLayout
        fun select(isLandscape: Boolean) {
            landscape = isLandscape
            view.landscape = isLandscape
            vertical.background = Lx.plate(host, if (!isLandscape) Theme.accent else Lx.SURFACE_HI)
            horizontal.background = Lx.plate(host, if (isLandscape) Theme.accent else Lx.SURFACE_HI)
        }
        vertical = Lx.button(host, T("Vertical"), Lx.Tone.SECONDARY, compact = true) { select(false) }
        horizontal = Lx.button(host, T("Horizontal"), Lx.Tone.SECONDARY, compact = true) { select(true) }
        tabs.addView(vertical, Lx.lp(host, -2f, -2f, end = 8f))
        tabs.addView(horizontal)
        root.addView(Lx.text(host, T("Position the picture"), 18f, Color.WHITE, Lx.BOLD, 1), Lx.lp(host, -2f, -2f, top = 28f, bottom = 4f, start = 16f))
        root.addView(Lx.caption(host, T("Drag to move it, pinch to zoom. Do this for both directions."), 0xFFB5BCC6.toInt(), 2), Lx.lp(host, -2f, -2f, bottom = 10f, start = 16f))
        root.addView(tabs, Lx.lp(host, -1f, -2f, bottom = 8f))
        root.addView(view, Lx.lp(host, -1f, 0f, 1f))

        val bar = Lx.hbox(host, Gravity.CENTER)
        bar.addView(Lx.button(host, T("Fill"), Lx.Tone.SECONDARY, compact = true) { view.fill() }, Lx.lp(host, -2f, -2f, end = 6f))
        bar.addView(Lx.button(host, T("Fit"), Lx.Tone.SECONDARY, compact = true) { view.fit() }, Lx.lp(host, -2f, -2f, end = 6f))
        bar.addView(Lx.button(host, T("Centre"), Lx.Tone.SECONDARY, compact = true) { view.centre() })
        root.addView(bar, Lx.lp(host, -1f, -2f, top = 10f))
        val done = Lx.hbox(host, Gravity.CENTER)
        done.addView(
            Lx.button(host, T("Cancel"), Lx.Tone.GHOST) {
                Theme.portrait.zoom = originalPortrait.zoom; Theme.portrait.cx = originalPortrait.cx; Theme.portrait.cy = originalPortrait.cy
                Theme.landscape.zoom = originalLandscape.zoom; Theme.landscape.cx = originalLandscape.cx; Theme.landscape.cy = originalLandscape.cy
                dialog.dismiss()
            },
            Lx.lp(host, -2f, -2f, end = 8f),
        )
        done.addView(
            Lx.button(host, T("Done"), Lx.Tone.PRIMARY) {
                Theme.saveFraming(host)
                dialog.dismiss()
                host.recreate()
            },
        )
        root.addView(done, Lx.lp(host, -1f, -2f, top = 8f, bottom = 18f))
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        select(host.resources.configuration.screenWidthDp > host.resources.configuration.screenHeightDp)
        dialog.show()
    }

    private class FramingView(context: Context, private val image: android.graphics.Bitmap, private val long: Float, private val short: Float) : View(context) {
        var landscape = false
            set(value) {
                field = value
                invalidate()
            }
        private val frame = RectF()
        private val placed = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f }
        private val framing get() = if (landscape) Theme.landscape else Theme.portrait
        private var lastX = 0f
        private var lastY = 0f
        private val scale =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        framing.zoom = (framing.zoom * d.scaleFactor).coerceIn(0.2f, 6f)
                        invalidate()
                        return true
                    }
                },
            )

        private fun layoutFrame() {
            val aspect = if (landscape) long / short else short / long
            val availW = width - 48f
            val availH = height - 24f
            var w = availW
            var h = w / aspect
            if (h > availH) {
                h = availH
                w = h * aspect
            }
            frame.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        }

        override fun onDraw(canvas: Canvas) {
            layoutFrame()
            canvas.save()
            canvas.clipRect(frame)
            canvas.drawColor(Theme.bg)
            Theme.place(image.width, image.height, frame.width(), frame.height(), framing, placed)
            placed.offset(frame.left, frame.top)
            canvas.drawBitmap(image, null, placed, paint)
            canvas.drawColor(Lx.alpha(Color.BLACK, Theme.dim))
            canvas.restore()
            canvas.drawRect(frame, border)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scale.onTouchEvent(e)
            if (scale.isInProgress) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1) {
                        val dw = max(placed.width(), 1f)
                        val dh = max(placed.height(), 1f)
                        framing.cx = (framing.cx - (e.x - lastX) / dw).coerceIn(0f, 1f)
                        framing.cy = (framing.cy - (e.y - lastY) / dh).coerceIn(0f, 1f)
                        invalidate()
                    }
                    lastX = e.x
                    lastY = e.y
                }
            }
            return true
        }

        fun fill() {
            framing.zoom = 1f
            invalidate()
        }

        fun fit() {
            layoutFrame()
            val cover = max(frame.width() / image.width, frame.height() / image.height)
            val contain = min(frame.width() / image.width, frame.height() / image.height)
            framing.zoom = contain / cover
            framing.cx = 0.5f
            framing.cy = 0.5f
            invalidate()
        }

        fun centre() {
            framing.cx = 0.5f
            framing.cy = 0.5f
            invalidate()
        }
    }
}
