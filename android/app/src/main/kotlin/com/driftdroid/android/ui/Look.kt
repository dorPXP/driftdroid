package com.driftdroid.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import java.util.Random
import kotlin.math.min

/**
 * How the launcher's surfaces are drawn. [FLAT] is the original "pit lane" look (solid fills and
 * slanted plates, drawn by Lx itself); the others change every panel, button and text box through
 * [LookDrawable].
 *
 * - [GLASS]: translucent frosted panels with a bright rim and a soft top sheen, like liquid glass.
 * - [AERO]: Frutiger Aero - saturated glossy buttons: dark at the top, bright at the bottom, an inset
 *   shine pane over the upper half, a glow along the bottom edge and a bright rim.
 * - [SKEUO]: skeuomorphic - chunky bevelled buttons and panels with a real material (brushed metal
 *   or leather), an engraved edge and stitching.
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

/** The surface material of the skeuomorphic look. */
internal enum class Texture(val id: String) {
    NONE("none"),
    METAL("metal"),
    LEATHER("leather"),
    ;

    companion object {
        fun of(id: String?): Texture = values().firstOrNull { it.id == id } ?: NONE
    }
}

/** Small tiles for [Texture], drawn once with a fixed seed. */
internal object Textures {
    private val cache = HashMap<Texture, Bitmap>()

    @Synchronized
    fun tile(texture: Texture): Bitmap =
        cache.getOrPut(texture) {
            val size = if (texture == Texture.METAL) 128 else 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val rnd = Random(if (texture == Texture.METAL) 5 else 9)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (texture == Texture.METAL) {
                // Fine horizontal streaks of light and dark, like a brushed finish.
                for (y in 0 until size) {
                    val light = rnd.nextBoolean()
                    val v = if (light) 255 else 0
                    p.color = Color.argb(6 + rnd.nextInt(30), v, v, v)
                    val x0 = rnd.nextInt(size / 3)
                    canvas.drawRect(x0.toFloat(), y.toFloat(), size.toFloat(), y + 1f, p)
                    canvas.drawRect(0f, y.toFloat(), (x0 / 2).toFloat(), y + 1f, p)
                }
            } else {
                // Grain: tiny dark pits with a lighter rim.
                repeat(520) {
                    val x = rnd.nextFloat() * size
                    val y = rnd.nextFloat() * size
                    val r = 0.7f + rnd.nextFloat() * 1.1f
                    p.color = Color.argb(34 + rnd.nextInt(30), 0, 0, 0)
                    canvas.drawCircle(x, y, r, p)
                    p.color = Color.argb(14 + rnd.nextInt(14), 255, 255, 255)
                    canvas.drawCircle(x - 0.6f, y - 0.6f, r * 0.7f, p)
                }
            }
            bitmap
        }
}

/**
 * A rounded surface for the non-flat looks. It is a [GradientDrawable] (so callers that tweak the
 * shape keep working) that also paints shine, glow, bevels, texture or stitching on top of its fill.
 *
 * [family] is true for the neutral surfaces (panels, rows, text wells), which turn translucent so the
 * backdrop shows through; coloured fills (the accent button, danger) stay solid and glossy.
 */
internal class LookDrawable(
    private val look: Look,
    base: Int,
    private val radiusPx: Float,
    strokeColor: Int,
    strokePx: Int,
    family: Boolean,
    private val panelAlpha: Float,
    private val light: Boolean,
    private val surfaceTone: Int = Theme.surface,
    private val texture: Texture = Theme.texture,
) : GradientDrawable() {
    /** A fully transparent fill (a ghost button, a list row) becomes a faint neutral surface. */
    private val invisible = Color.alpha(base) == 0 && strokeColor == 0 && look != Look.FLAT
    private val ghost = Color.alpha(base) == 0 && !invisible && look != Look.FLAT
    private val isFamily = family || ghost
    private val fade = if (ghost) 0.55f else 1f
    private val edge = strokePx.coerceAtLeast(1)

    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val rect = RectF()
    private val pane = RectF()
    private var shaderKey = -1
    private var shineShader: Shader? = null
    private var glowShader: Shader? = null
    private var bevelLight: Shader? = null
    private var bevelDark: Shader? = null
    private var textureShader: BitmapShader? = null

    init {
        shape = RECTANGLE
        orientation = Orientation.TOP_BOTTOM
        cornerRadius = radiusPx
        val white = Color.WHITE
        if (invisible) {
            // A plain transparent row (inside a panel, on a bar): nothing to draw, so layers do not stack up.
            setColor(Color.TRANSPARENT)
        } else when (look) {
            Look.GLASS -> {
                if (isFamily) {
                    if (light) {
                        val a = panelAlpha * fade
                        colors = intArrayOf(Lx.alpha(white, a + 0.10f), Lx.alpha(white, (a - 0.04f).coerceAtLeast(0.03f)))
                        setStroke(edge, Lx.alpha(white, 0.75f))
                    } else {
                        colors = darkGlass(panelAlpha * fade)
                        setStroke(edge, Lx.alpha(white, 0.28f))
                    }
                } else {
                    colors = intArrayOf(Lx.alpha(lighten(base, 0.30f), 0.95f), Lx.alpha(base, 0.90f))
                    setStroke(edge, Lx.alpha(white, 0.50f))
                }
            }
            Look.AERO -> {
                if (isFamily) {
                    colors =
                        if (light) {
                            intArrayOf(Lx.alpha(white, 0.80f * fade), Lx.alpha(white, 0.52f * fade))
                        } else {
                            darkGlass(panelAlpha * fade)
                        }
                    setStroke(edge, Lx.alpha(white, if (light) 0.95f else 0.55f))
                } else {
                    // Dark and saturated at the top, bright at the bottom: light seen through coloured glass.
                    setColors(intArrayOf(darken(base, 0.34f), base, lighten(shiftHue(base, -14f), 0.52f)), floatArrayOf(0f, 0.55f, 1f))
                    setStroke(edge.coerceAtLeast(2), darken(base, 0.50f))
                }
            }
            Look.SKEUO -> {
                if (isFamily) {
                    val s = surfaceTone
                    setColors(intArrayOf(lighten(s, 0.16f), s, darken(s, 0.14f)), floatArrayOf(0f, 0.5f, 1f))
                    setStroke(edge, Lx.alpha(Color.BLACK, 0.60f))
                } else {
                    setColors(intArrayOf(lighten(base, 0.42f), base, darken(base, 0.30f)), floatArrayOf(0f, 0.5f, 1f))
                    setStroke(edge.coerceAtLeast(2), darken(base, 0.62f))
                }
            }
            Look.FLAT -> {
                setColor(base)
                if (strokeColor != 0) setStroke(edge, strokeColor)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (invisible) return
        val b = bounds
        if (b.width() < 8 || b.height() < 8) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        ensureShaders(b.height())
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        rect.set(0f, 0f, w, h)
        val inset = edge * 0.75f
        rect.inset(inset, inset)
        val r = (radiusPx - inset).coerceAtLeast(0f)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        when (look) {
            Look.GLASS -> {
                sheen.shader = shineShader
                canvas.drawRect(rect.left, 0f, rect.right, h * 0.55f, sheen)
            }
            Look.AERO ->
                if (isFamily) {
                    sheen.shader = shineShader
                    canvas.drawRect(rect.left, 0f, rect.right, h * 0.42f, sheen)
                } else {
                    // The glow along the bottom edge.
                    sheen.shader = glowShader
                    canvas.drawRect(rect.left, h * 0.55f, rect.right, h, sheen)
                }
            Look.SKEUO ->
                if (isFamily && textureShader != null) {
                    sheen.shader = textureShader
                    canvas.drawRect(rect, sheen)
                }
            Look.FLAT -> {}
        }
        sheen.shader = null
        canvas.restore()
        // Edge details, drawn over the clipped layers.
        when (look) {
            Look.GLASS -> {}
            Look.AERO ->
                if (!isFamily) {
                    // A thin bright rim inside the dark outline, then the inset shine pane.
                    line.shader = null
                    line.strokeWidth = 1f
                    line.color = Lx.alpha(Color.WHITE, 0.55f)
                    canvas.drawRoundRect(rect, r, r, line)
                    val side = w * 0.075f
                    pane.set(side, h * 0.07f, w - side, h * 0.50f)
                    val rr = min(pane.height() / 2f, r * 0.85f)
                    sheen.shader = shineShader
                    canvas.drawRoundRect(pane, rr, rr, sheen)
                    sheen.shader = null
                } else {
                    line.shader = null
                    line.strokeWidth = 1f
                    line.color = Lx.alpha(Color.WHITE, if (light) 0.9f else 0.35f)
                    canvas.drawRoundRect(rect, r, r, line)
                }
            Look.SKEUO -> {
                line.strokeWidth = 1.5f
                line.pathEffect = null
                line.shader = bevelLight
                canvas.drawRoundRect(rect, r, r, line)
                line.shader = bevelDark
                canvas.drawRoundRect(rect, r, r, line)
                line.shader = null
                if (isFamily && w > 260f && h > 120f) {
                    // Stitching, inset from the edge.
                    line.color = Lx.alpha(Color.WHITE, if (texture == Texture.LEATHER) 0.30f else 0.14f)
                    line.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
                    val s = RectF(rect)
                    s.inset(10f, 10f)
                    val sr = (r - 10f).coerceAtLeast(2f)
                    canvas.drawRoundRect(s, sr, sr, line)
                    line.pathEffect = null
                }
            }
            Look.FLAT -> {}
        }
        canvas.restore()
    }

    private fun ensureShaders(height: Int) {
        if (shaderKey == height) return
        shaderKey = height
        val h = height.toFloat()
        shineShader =
            when (look) {
                Look.GLASS -> LinearGradient(0f, 0f, 0f, h * 0.55f, Lx.alpha(Color.WHITE, if (light) 0.40f else 0.20f), Lx.alpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
                Look.AERO ->
                    if (isFamily) {
                        LinearGradient(0f, 0f, 0f, h * 0.42f, Lx.alpha(Color.WHITE, if (light) 0.55f else 0.22f), Lx.alpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
                    } else {
                        // The shine pane: bright at its top edge, fading as it goes down.
                        LinearGradient(0f, h * 0.07f, 0f, h * 0.50f, Lx.alpha(Color.WHITE, 0.78f), Lx.alpha(Color.WHITE, 0.16f), Shader.TileMode.CLAMP)
                    }
                else -> null
            }
        glowShader = LinearGradient(0f, h, 0f, h * 0.55f, Lx.alpha(Color.WHITE, 0.42f), Lx.alpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
        // A light edge along the top and a dark one along the bottom, fading toward the middle.
        bevelLight = LinearGradient(0f, 0f, 0f, h * 0.5f, Lx.alpha(Color.WHITE, 0.60f), Lx.alpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
        bevelDark = LinearGradient(0f, h, 0f, h * 0.5f, Lx.alpha(Color.BLACK, 0.45f), Lx.alpha(Color.BLACK, 0f), Shader.TileMode.CLAMP)
        textureShader = if (texture != Texture.NONE) BitmapShader(Textures.tile(texture), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) else null
    }

    companion object {
        /** A dark, slightly blue glass: white text stays readable on any backdrop behind it. */
        private fun darkGlass(alpha: Float): IntArray {
            val a = (alpha * 1.55f).coerceIn(0.10f, 0.90f)
            return intArrayOf(Color.argb((255 * (a + 0.06f).coerceAtMost(0.92f)).toInt(), 16, 26, 40), Color.argb((255 * (a - 0.04f).coerceAtLeast(0.06f)).toInt(), 10, 18, 30))
        }

        /** [color] with its hue turned by [degrees] (toward cyan for a blue, which is how Aero buttons glow). */
        fun shiftHue(color: Int, degrees: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[0] = (hsv[0] + degrees + 360f) % 360f
            return Color.HSVToColor(Color.alpha(color), hsv)
        }

        fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

        fun darken(color: Int, amount: Float): Int = blend(color, Color.BLACK, amount)

        private fun blend(a: Int, b: Int, amount: Float): Int {
            fun ch(x: Int, y: Int) = (x + (y - x) * amount).toInt().coerceIn(0, 255)
            return Color.argb(Color.alpha(a), ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
        }
    }
}
