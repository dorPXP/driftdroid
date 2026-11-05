package com.driftdroid.android.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable

/**
 * How the launcher's surfaces are drawn. [FLAT] is the original "pit lane" look (solid fills and
 * slanted plates, drawn by Lx itself); the others change every panel, button and text box through
 * [LookDrawable].
 *
 * - [GLASS]: translucent frosted panels with a bright rim and a soft top sheen, like liquid glass.
 * - [AERO]: Frutiger Aero - glossy aqua buttons with the classic half-ellipse shine, bright white rims.
 * - [SKEUO]: skeuomorphic - bevelled brushed-metal panels, an engraved edge and stitching.
 */
internal enum class Look(val id: String) {
    FLAT("flat"),
    GLASS("glass"),
    AERO("aero"),
    SKEUO("skeuo"),
    ;

    companion object {
        fun of(id: String?): Look = values().firstOrNull { it.id == id } ?: FLAT
    }
}

/**
 * A rounded surface for the non-flat looks. It is a [GradientDrawable] (so callers that tweak the
 * shape keep working) that also paints a sheen, a rim light or stitching on top of its fill.
 *
 * [family] is true for the neutral surfaces (panels, rows, text wells), which turn translucent so the
 * wallpaper shows through; coloured fills (the accent button, danger) stay solid and glossy.
 */
internal class LookDrawable(
    private val look: Look,
    base: Int,
    private val radiusPx: Float,
    strokeColor: Int,
    strokePx: Int,
    private val family: Boolean,
    private val panelAlpha: Float,
    private val light: Boolean,
) : GradientDrawable() {
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val rect = RectF()
    private var sheenShader: Shader? = null
    private var shaderHeight = -1

    init {
        shape = RECTANGLE
        orientation = Orientation.TOP_BOTTOM
        cornerRadius = radiusPx
        val white = Color.WHITE
        when (look) {
            Look.GLASS -> {
                if (family) {
                    val a = if (light) panelAlpha else panelAlpha * 0.55f
                    colors = intArrayOf(Lx.alpha(white, (a + 0.10f)), Lx.alpha(white, (a - 0.04f).coerceAtLeast(0.03f)))
                    setStroke(strokePx.coerceAtLeast(1), Lx.alpha(white, if (light) 0.75f else 0.30f))
                } else {
                    colors = intArrayOf(Lx.alpha(lighten(base, 0.30f), 0.95f), Lx.alpha(base, 0.90f))
                    setStroke(strokePx.coerceAtLeast(1), Lx.alpha(white, 0.50f))
                }
            }
            Look.AERO -> {
                if (family) {
                    colors =
                        if (light) {
                            intArrayOf(Lx.alpha(white, 0.80f), Lx.alpha(white, 0.52f))
                        } else {
                            intArrayOf(Lx.alpha(white, 0.32f), Lx.alpha(white, 0.12f))
                        }
                    setStroke(strokePx.coerceAtLeast(1), Lx.alpha(white, if (light) 0.95f else 0.55f))
                } else {
                    colors = intArrayOf(lighten(base, 0.55f), lighten(base, 0.18f), base, darken(base, 0.14f))
                    setStroke(strokePx.coerceAtLeast(1), Lx.alpha(white, 0.85f))
                }
            }
            Look.SKEUO -> {
                if (family) {
                    val s = Theme.surface
                    colors = intArrayOf(lighten(s, 0.14f), darken(s, 0.10f))
                    setStroke(strokePx.coerceAtLeast(1), Lx.alpha(Color.BLACK, 0.55f))
                } else {
                    colors = intArrayOf(lighten(base, 0.30f), base, darken(base, 0.20f))
                    setStroke(strokePx.coerceAtLeast(1), darken(base, 0.50f))
                }
            }
            Look.FLAT -> {
                setColor(base)
                if (strokeColor != 0) setStroke(strokePx.coerceAtLeast(1), strokeColor)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val b = bounds
        if (b.width() < 8 || b.height() < 8) return
        rect.set(b)
        val inset = 1.5f
        rect.inset(inset, inset)
        val r = (radiusPx - inset).coerceAtLeast(0f)
        when (look) {
            Look.GLASS -> {
                // A soft sheen over the top half, fading out.
                ensureShader(b.height(), 0.55f, if (light) 0.40f else 0.20f, 0f)
                sheen.shader = sheenShader
                canvas.save()
                clip.reset()
                clip.addRoundRect(rect, r, r, Path.Direction.CW)
                canvas.clipPath(clip)
                canvas.translate(0f, b.top.toFloat())
                canvas.drawRect(rect.left, 0f, rect.right, b.height() * 0.55f, sheen)
                canvas.restore()
            }
            Look.AERO -> {
                // The classic shine: a bright half-ellipse over the top half.
                ensureShader(b.height(), 0.52f, 0.62f, 0.10f)
                sheen.shader = sheenShader
                canvas.save()
                clip.reset()
                clip.addRoundRect(rect, r, r, Path.Direction.CW)
                canvas.clipPath(clip)
                val shine = RectF(rect.left + rect.width() * 0.03f, rect.top, rect.right - rect.width() * 0.03f, rect.top + rect.height() * 0.52f)
                canvas.translate(0f, b.top.toFloat())
                shine.offset(0f, -b.top.toFloat())
                canvas.drawRoundRect(shine, r, r, sheen)
                canvas.restore()
            }
            Look.SKEUO -> {
                // An engraved edge: light along the top inside edge, dark along the bottom.
                line.strokeWidth = 1f
                line.pathEffect = null
                line.color = Lx.alpha(Color.WHITE, 0.38f)
                canvas.drawLine(rect.left + r * 0.6f, rect.top + 0.5f, rect.right - r * 0.6f, rect.top + 0.5f, line)
                line.color = Lx.alpha(Color.BLACK, 0.35f)
                canvas.drawLine(rect.left + r * 0.6f, rect.bottom - 0.5f, rect.right - r * 0.6f, rect.bottom - 0.5f, line)
                if (family && b.width() > 260 && b.height() > 120) {
                    // Stitching, inset from the edge.
                    line.color = Lx.alpha(Color.WHITE, 0.16f)
                    line.pathEffect = DashPathEffect(floatArrayOf(7f, 6f), 0f)
                    val s = RectF(rect)
                    s.inset(9f, 9f)
                    canvas.drawRoundRect(s, (r - 9f).coerceAtLeast(2f), (r - 9f).coerceAtLeast(2f), line)
                }
            }
            Look.FLAT -> {}
        }
    }

    private fun ensureShader(height: Int, fraction: Float, topAlpha: Float, bottomAlpha: Float) {
        if (sheenShader != null && shaderHeight == height) return
        shaderHeight = height
        sheenShader =
            LinearGradient(
                0f, 0f, 0f, height * fraction,
                Lx.alpha(Color.WHITE, topAlpha), Lx.alpha(Color.WHITE, bottomAlpha), Shader.TileMode.CLAMP,
            )
    }

    companion object {
        fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

        fun darken(color: Int, amount: Float): Int = blend(color, Color.BLACK, amount)

        private fun blend(a: Int, b: Int, amount: Float): Int {
            fun ch(x: Int, y: Int) = (x + (y - x) * amount).toInt().coerceIn(0, 255)
            return Color.argb(Color.alpha(a), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
        }
    }
}
