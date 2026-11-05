package com.driftdroid.android.mii

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.IconDrawable
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.RemoteImages
import kotlin.math.max

/**
 * Where a Mii part sits in the renderer's face picture, and how far to zoom in on it, so a picker
 * tile shows the eyes as eyes rather than a whole head with a dot in it. Fractions of the picture.
 */
internal class PartFocus(val x: Float, val y: Float, val zoom: Float) {
    companion object {
        val WHOLE = PartFocus(0.5f, 0.5f, 1f)
    }
}

/** An ImageView that crops its picture around a [PartFocus] instead of fitting it whole. */
internal class FocusImageView(context: Context) : ImageView(context) {
    var focus: PartFocus = PartFocus.WHOLE
        set(value) {
            field = value
            updateMatrix()
        }
    private val crop = Matrix()

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateMatrix()
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        updateMatrix()
        return changed
    }

    private fun updateMatrix() {
        val d = drawable
        val dw = d?.intrinsicWidth ?: -1
        val dh = d?.intrinsicHeight ?: -1
        if (d == null || dw <= 0 || dh <= 0 || width == 0 || height == 0) {
            // A drawn placeholder without a size of its own just fills the tile.
            if (scaleType != ScaleType.FIT_XY) scaleType = ScaleType.FIT_XY
            return
        }
        if (scaleType != ScaleType.MATRIX) scaleType = ScaleType.MATRIX
        val scale = max(width.toFloat() / dw, height.toFloat() / dh) * focus.zoom
        val tx = (width / 2f - focus.x * dw * scale).coerceIn(width - dw * scale, 0f)
        val ty = (height / 2f - focus.y * dh * scale).coerceIn(height - dh * scale, 0f)
        crop.setScale(scale, scale)
        crop.postTranslate(tx, ty)
        imageMatrix = crop
    }
}

/**
 * One choice in a Mii part picker: the Mii as it would look with that part, zoomed onto the part,
 * outlined green when it's the current one. Offline (or with Mii pictures turned off) it falls
 * back to the part's number.
 */
internal class PartTile(context: Context, number: String, caption: String? = null) : FrameLayout(context) {
    private val image = FocusImageView(context)
    private val placeholder = NumberDrawable(number)
    private val check = ImageView(context)

    init {
        background = Lx.pressable(context, Lx.SURFACE_HI, 8f)
        Lx.clipRounded(this, 8f)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (caption != null) {
            // "None" says so in words: a face without glasses doesn't explain itself.
            val label = Lx.label(context, caption, Lx.TEXT, 10f)
            label.gravity = Gravity.CENTER
            label.setPadding(0, Lx.dp(context, 3f), 0, Lx.dp(context, 3f))
            label.setBackgroundColor(Lx.alpha(Lx.BG, 0.72f))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        check.setImageDrawable(IconDrawable(Icon.CHECK, Lx.ACCENT_TEXT))
        val p = Lx.dp(context, 2f)
        check.setPadding(p, p, p, p)
        check.background = Lx.rounded(context, Lx.ACCENT, 4f)
        check.visibility = View.GONE
        addView(check, LayoutParams(Lx.dp(context, 18f), Lx.dp(context, 18f), Gravity.TOP or Gravity.END).apply {
            setMargins(0, Lx.dp(context, 4f), Lx.dp(context, 4f), 0)
        })
        contentDescription = number
    }

    fun setPicked(picked: Boolean) {
        foreground = if (picked) Lx.rounded(context, 0, 8f, Lx.ACCENT, 2.5f) else null
        check.visibility = if (picked) View.VISIBLE else View.GONE
        isSelected = picked
    }

    /** Shows [mii] cropped to [focus]; [online] false shows the number only. */
    fun show(mii: Mii, focus: PartFocus, online: Boolean) {
        image.focus = focus
        if (!online) {
            RemoteImages.clear(image)
            image.setImageDrawable(placeholder)
            return
        }
        // Ask for enough pixels that the zoomed crop is still sharp.
        val size = if (focus.zoom > 1.4f) MiiStudio.Size.XL else MiiStudio.Size.LARGE
        val target = (Lx.dp(context, 80f) * focus.zoom).toInt().coerceAtMost(size.px)
        RemoteImages.bind(image, MiiStudio.imageUrl(mii, size), target, placeholder)
    }
}

/** A part's number, drawn quietly in the middle of its tile until (or instead of) its picture. */
private class NumberDrawable(private val text: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Lx.FAINT
        typeface = Lx.DISPLAY
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.textSize = b.height() * 0.3f
        canvas.drawText(text, b.exactCenterX(), b.exactCenterY() + paint.textSize * 0.35f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Height and weight at a glance: the Mii as a simple figure in its favourite-colour shirt, drawn
 * over the dashed outline of an average Mii (both 64), so "taller" and "heavier" read against
 * something. It follows the sliders instantly and works offline.
 */
internal class BodyFigure(context: Context) : View(context) {
    private var heightValue = 64
    private var weightValue = 64
    private var shirt = Lx.ACCENT
    private var skin = Mii.SKIN_COLORS[0]
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Lx.FAINT
        strokeWidth = Lx.dp(context, 1.5f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(Lx.dp(context, 4f).toFloat(), Lx.dp(context, 3f).toFloat()), 0f)
    }
    private val ground = Paint().apply {
        color = Lx.LINE
        strokeWidth = Lx.dp(context, 1f).toFloat()
    }
    private val rect = RectF()

    fun set(mii: Mii) {
        heightValue = mii.height
        weightValue = mii.weight
        shirt = Mii.FAVORITE_COLORS[mii.favoriteColor.coerceIn(0, Mii.FAVORITE_COLORS.size - 1)]
        skin = Mii.SKIN_COLORS[mii.skinColor.coerceIn(0, Mii.SKIN_COLORS.size - 1)]
        contentDescription = "Height $heightValue of 127, weight $weightValue of 127"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = Lx.dp(context, 10f).toFloat()
        val baseline = height - pad
        val unit = (height - pad * 2) / 1.34f
        canvas.drawLine(pad, baseline, width - pad, baseline, ground)
        drawFigure(canvas, width / 2f, baseline, unit, 64, 64, null)
        drawFigure(canvas, width / 2f, baseline, unit, heightValue, weightValue, fill)
    }

    /** Head, torso, arms and legs, standing on [baseline]; [paint] null draws the dashed outline. */
    private fun drawFigure(canvas: Canvas, cx: Float, baseline: Float, unit: Float, h: Int, w: Int, paint: Paint?) {
        val tall = 0.72f + 0.5f * (h / 127f)
        val wide = 0.7f + 0.75f * (w / 127f)
        val legH = 0.38f * unit * tall
        val legW = 0.12f * unit * wide
        val gap = 0.03f * unit
        val torsoH = 0.42f * unit * tall
        val torsoW = 0.36f * unit * wide
        val armW = 0.09f * unit * wide
        val armH = 0.34f * unit * tall
        val head = 0.17f * unit
        val torsoTop = baseline - legH - torsoH
        val r = 0.06f * unit

        fun part(l: Float, t: Float, rr: Float, b: Float, color: Int, radius: Float) {
            rect.set(l, t, rr, b)
            if (paint == null) {
                canvas.drawRoundRect(rect, radius, radius, ghost)
            } else {
                paint.color = color
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
        // Legs, in the dark trousers every Mii wears.
        part(cx - gap / 2 - legW, baseline - legH, cx - gap / 2, baseline, 0xFF3A3F47.toInt(), r)
        part(cx + gap / 2, baseline - legH, cx + gap / 2 + legW, baseline, 0xFF3A3F47.toInt(), r)
        // Arms either side, hands in skin tone at the ends.
        val armTop = torsoTop + 0.04f * unit
        part(cx - torsoW / 2 - armW - 0.01f * unit, armTop, cx - torsoW / 2 - 0.01f * unit, armTop + armH, shirt, armW / 2)
        part(cx + torsoW / 2 + 0.01f * unit, armTop, cx + torsoW / 2 + armW + 0.01f * unit, armTop + armH, shirt, armW / 2)
        if (paint != null) {
            paint.color = skin
            canvas.drawCircle(cx - torsoW / 2 - armW / 2 - 0.01f * unit, armTop + armH, armW * 0.62f, paint)
            canvas.drawCircle(cx + torsoW / 2 + armW / 2 + 0.01f * unit, armTop + armH, armW * 0.62f, paint)
        }
        part(cx - torsoW / 2, torsoTop, cx + torsoW / 2, baseline - legH + 0.02f * unit, shirt, r * 1.4f)
        // Head: the same size whatever the build, as on a real Mii.
        val headY = torsoTop - head + 0.02f * unit
        if (paint == null) canvas.drawCircle(cx, headY, head, ghost) else {
            paint.color = skin
            canvas.drawCircle(cx, headY, head, paint)
        }
    }
}
