package com.driftdroid.android.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.driftdroid.android.ui.Look
import com.driftdroid.android.ui.LookDrawable
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.Theme
import com.driftdroid.android.ui.Texture
import kotlin.math.max

/**
 * A small picture of a theme (or just a wallpaper): the backdrop, a card, a button and a tab strip,
 * drawn with the same [LookDrawable] the launcher uses. Shown in a swipeable row in Appearance so
 * you can see each one before picking it.
 */
internal class ThemePreview(
    context: Context,
    private val look: Look,
    private val top: Int,
    private val bottom: Int,
    private val accent: Int,
    private val texture: Texture = Texture.NONE,
    private val backdropOnly: Boolean = false,
) : View(context) {
    var picked = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 14 * density
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        drawBackdrop(canvas, w, h)
        if (!backdropOnly) drawMiniUi(canvas, w, h)
        canvas.restore()
        ring.strokeWidth = (if (picked) 3.5f else 1f) * density
        ring.color = if (picked) accent else Lx.alpha(Color.WHITE, 0.35f)
        val inset = ring.strokeWidth / 2f
        canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), radius, radius, ring)
    }

    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawMiniUi(canvas: Canvas, w: Float, h: Float) {
        val light = Theme.lightFor(top, bottom)
        val surface = LookDrawable.lighten(top, 0.06f)
        // A card.
        val card = RectF(w * 0.08f, h * 0.12f, w * 0.92f, h * 0.60f)
        if (look == Look.FLAT) {
            paint.color = if (light) 0xFFFFFFFF.toInt() else LookDrawable.lighten(average(top, bottom), 0.07f)
            canvas.drawRoundRect(card, 6 * density, 6 * density, paint)
        } else {
            val d = LookDrawable(look, surface, radiusFor(look), 0, 1, true, Theme.panelAlpha, light, surface, texture)
            d.setBounds(card.left.toInt(), card.top.toInt(), card.right.toInt(), card.bottom.toInt())
            d.draw(canvas)
        }
        // Text stand-ins.
        paint.color = Lx.alpha(if (light) 0xFF14171C.toInt() else Color.WHITE, 0.80f)
        canvas.drawRoundRect(RectF(w * 0.15f, h * 0.18f, w * 0.60f, h * 0.215f), 3 * density, 3 * density, paint)
        paint.color = Lx.alpha(if (light) 0xFF14171C.toInt() else Color.WHITE, 0.45f)
        canvas.drawRoundRect(RectF(w * 0.15f, h * 0.245f, w * 0.45f, h * 0.275f), 3 * density, 3 * density, paint)
        // A button.
        val button = RectF(w * 0.14f, h * 0.40f, w * 0.86f, h * 0.51f)
        if (look == Look.FLAT) {
            val lean = button.height() * 0.21f
            val path = Path().apply {
                moveTo(button.left + lean, button.top)
                lineTo(button.right, button.top)
                lineTo(button.right - lean, button.bottom)
                lineTo(button.left, button.bottom)
                close()
            }
            paint.color = accent
            canvas.drawPath(path, paint)
        } else {
            val d = LookDrawable(look, accent, button.height() / 2f, 0, 1, false, Theme.panelAlpha, light, surface, texture)
            d.setBounds(button.left.toInt(), button.top.toInt(), button.right.toInt(), button.bottom.toInt())
            d.draw(canvas)
        }
        // A tab strip with the first tab picked.
        val bar = RectF(0f, h * 0.88f, w, h)
        paint.color = if (look == Look.FLAT) LookDrawable.darken(average(top, bottom), 0.2f) else Lx.alpha(if (light) Color.WHITE else Color.BLACK, if (light) 0.55f else 0.35f)
        canvas.drawRect(bar, paint)
        for (i in 0 until 3) {
            paint.color = if (i == 0) accent else Lx.alpha(if (light) 0xFF14171C.toInt() else Color.WHITE, 0.55f)
            canvas.drawCircle(w * (0.25f + 0.25f * i), h * 0.94f, 3.2f * density, paint)
        }
    }

    private fun radiusFor(look: Look): Float =
        when (look) {
            Look.GLASS -> 12 * density
            Look.AERO -> 14 * density
            else -> 7 * density
        }

    private fun average(a: Int, b: Int): Int = LookDrawable.lighten(a, 0f).let { x ->
        val r = (Color.red(a) + Color.red(b)) / 2
        val g = (Color.green(a) + Color.green(b)) / 2
        val bl = (Color.blue(a) + Color.blue(b)) / 2
        Color.rgb(r, g, bl).also { x.hashCode() }
    }
}
