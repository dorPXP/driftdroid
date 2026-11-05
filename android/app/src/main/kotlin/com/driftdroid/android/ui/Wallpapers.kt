package com.driftdroid.android.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Built-in wallpapers, drawn in code rather than shipped as pictures: nothing to download, nothing
 * from anyone else's artwork, and they fit any screen shape. Each is deterministic (a fixed random
 * seed), so it looks the same every time. [light] tells the theme whether text on it should be dark.
 */
internal enum class Wallpaper(val id: String, val light: Boolean) {
    AQUA("aqua", false),
    BUBBLES("bubbles", false),
    MEADOW("meadow", true),
    SUNSET("sunset", false),
    AURORA("aurora", false),
    DEEP("deep", false),
    ;

    companion object {
        fun of(id: String?): Wallpaper? = values().firstOrNull { it.id == id }
    }
}

internal object Wallpapers {
    private const val LONG_SIDE = 960
    private val cache = HashMap<String, Bitmap>()

    /** The wallpaper at roughly the screen's shape, drawn once and kept. Draw it scaled to fit. */
    @Synchronized
    fun get(wallpaper: Wallpaper, width: Int, height: Int): Bitmap {
        val scale = LONG_SIDE.toFloat() / max(width, height).coerceAtLeast(1)
        val w = (width * min(scale, 1f)).toInt().coerceAtLeast(64)
        val h = (height * min(scale, 1f)).toInt().coerceAtLeast(64)
        val key = "${wallpaper.id}:$w:$h"
        return cache.getOrPut(key) {
            if (cache.size > 4) cache.clear()
            render(wallpaper, w, h)
        }
    }

    private fun render(wallpaper: Wallpaper, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        when (wallpaper) {
            Wallpaper.AQUA -> aqua(c, w, h)
            Wallpaper.BUBBLES -> bubbles(c, w, h)
            Wallpaper.MEADOW -> meadow(c, w, h)
            Wallpaper.SUNSET -> sunset(c, w, h)
            Wallpaper.AURORA -> aurora(c, w, h)
            Wallpaper.DEEP -> deep(c, w, h)
        }
        return bitmap
    }

    // --- Shared pieces ---

    private fun vertical(c: Canvas, w: Int, h: Int, vararg colors: Int) {
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, null, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun glow(c: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, radius, Lx.alpha(color, alpha), Lx.alpha(color, 0f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(x, y, radius, p)
    }

    /** A soap bubble: a faint body, a bright rim and a small highlight. */
    private fun bubble(c: Canvas, x: Float, y: Float, r: Float, tint: Int = Color.WHITE) {
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                x - r * 0.25f, y - r * 0.3f, r * 1.1f,
                intArrayOf(Lx.alpha(tint, 0.05f), Lx.alpha(tint, 0.10f), Lx.alpha(tint, 0.34f)),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(x, y, r, body)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, r * 0.05f)
            color = Lx.alpha(Color.WHITE, 0.45f)
        }
        c.drawCircle(x, y, r - rim.strokeWidth / 2f, rim)
        val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Lx.alpha(Color.WHITE, 0.75f) }
        c.drawCircle(x - r * 0.38f, y - r * 0.42f, r * 0.13f, hi)
        val hi2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Lx.alpha(Color.WHITE, 0.35f) }
        c.drawCircle(x + r * 0.32f, y + r * 0.40f, r * 0.07f, hi2)
    }

    private fun bubbles(c: Canvas, w: Int, h: Int, count: Int, seed: Long, minR: Float, maxR: Float, lowBias: Float = 1f) {
        val rnd = Random(seed)
        repeat(count) {
            val r = minR + (maxR - minR) * rnd.nextFloat().let { it * it }
            val y = h * (rnd.nextFloat().let { Math.pow(it.toDouble(), lowBias.toDouble()).toFloat() })
            bubble(c, w * rnd.nextFloat(), y, r)
        }
    }

    private fun wave(c: Canvas, w: Int, baseY: Float, amp: Float, length: Float, phase: Float, h: Int, fill: Int, crest: Int = 0) {
        val path = Path()
        path.moveTo(0f, h.toFloat())
        var x = 0f
        while (x <= w + 8) {
            path.lineTo(x, baseY + amp * sin((x / length) * 2 * PI + phase).toFloat())
            x += 8f
        }
        path.lineTo(w.toFloat(), h.toFloat())
        path.close()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        if (crest != 0) {
            val line = Path()
            x = 0f
            line.moveTo(0f, baseY + amp * sin(phase.toDouble()).toFloat())
            while (x <= w + 8) {
                line.lineTo(x, baseY + amp * sin((x / length) * 2 * PI + phase).toFloat())
                x += 8f
            }
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1.5f, w * 0.003f); color = crest })
        }
    }

    private fun rays(c: Canvas, w: Int, h: Int, originX: Float, count: Int, seed: Long, alpha: Float) {
        val rnd = Random(seed)
        repeat(count) {
            val spread = w * (0.04f + rnd.nextFloat() * 0.10f)
            val tipX = originX + (rnd.nextFloat() - 0.5f) * w * 0.6f
            val baseX = tipX + (rnd.nextFloat() - 0.2f) * w * 0.9f
            val path = Path().apply {
                moveTo(tipX - spread * 0.2f, 0f)
                lineTo(tipX + spread * 0.2f, 0f)
                lineTo(baseX + spread, h * 0.95f)
                lineTo(baseX - spread, h * 0.95f)
                close()
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, h * 0.95f, Lx.alpha(Color.WHITE, alpha * (0.5f + rnd.nextFloat())), Lx.alpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
            }
            c.drawPath(path, p)
        }
    }

    private fun bokeh(c: Canvas, w: Int, h: Int, colors: IntArray, count: Int, seed: Long, minR: Float, maxR: Float, alpha: Float) {
        val rnd = Random(seed)
        repeat(count) {
            val r = minR + (maxR - minR) * rnd.nextFloat()
            glow(c, w * rnd.nextFloat(), h * rnd.nextFloat(), r, colors[rnd.nextInt(colors.size)], alpha * (0.4f + 0.6f * rnd.nextFloat()))
        }
    }

    // --- The wallpapers ---

    private fun aqua(c: Canvas, w: Int, h: Int) {
        vertical(c, w, h, 0xFF8DEBFF.toInt(), 0xFF2CB8F0.toInt(), 0xFF0F78C8.toInt(), 0xFF0A4C94.toInt(), 0xFF07306A.toInt())
        glow(c, w * 0.78f, -h * 0.02f, w * 0.9f, Color.WHITE, 0.55f)
        rays(c, w, h, w * 0.65f, 7, 11, 0.20f)
        wave(c, w, h * 0.18f, h * 0.010f, w * 0.9f, 0.4f, h, Lx.alpha(Color.WHITE, 0.10f), Lx.alpha(Color.WHITE, 0.45f))
        wave(c, w, h * 0.24f, h * 0.012f, w * 0.7f, 2.1f, h, Lx.alpha(0xFF0A5FB4.toInt(), 0.18f), Lx.alpha(Color.WHITE, 0.25f))
        wave(c, w, h * 0.31f, h * 0.014f, w * 1.1f, 4.0f, h, Lx.alpha(0xFF043A7C.toInt(), 0.22f))
        bubbles(c, w, h, 30, 5, w * 0.012f, w * 0.085f, 0.8f)
        // Soft caustic flecks near the surface.
        val rnd = Random(21)
        repeat(60) { glow(c, w * rnd.nextFloat(), h * 0.06f + h * 0.4f * rnd.nextFloat(), w * (0.02f + 0.05f * rnd.nextFloat()), Color.WHITE, 0.10f) }
    }

    private fun bubbles(c: Canvas, w: Int, h: Int) {
        val g = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF22D3C5.toInt(), 0xFF1FA2E8.toInt(), 0xFF6A5BEA.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        bokeh(c, w, h, intArrayOf(0xFFB6FFF2.toInt(), 0xFFFFFFFF.toInt(), 0xFFA6E3FF.toInt(), 0xFFD0B8FF.toInt(), 0xFFFFF3B0.toInt()), 38, 3, w * 0.05f, w * 0.30f, 0.34f)
        bubbles(c, w, h, 22, 8, w * 0.015f, w * 0.10f)
    }

    private fun meadow(c: Canvas, w: Int, h: Int) {
        vertical(c, w, h, 0xFF2E9BEF.toInt(), 0xFF7CCBFF.toInt(), 0xFFCDEEFF.toInt())
        glow(c, w * 0.85f, h * 0.10f, w * 0.7f, Color.WHITE, 0.8f)
        // Clouds: clusters of soft ellipses.
        val rnd = Random(2)
        repeat(7) {
            val cx = w * rnd.nextFloat()
            val cy = h * (0.08f + 0.30f * rnd.nextFloat())
            val size = w * (0.10f + 0.12f * rnd.nextFloat())
            repeat(5) { glow(c, cx + (rnd.nextFloat() - 0.5f) * size * 1.6f, cy + (rnd.nextFloat() - 0.5f) * size * 0.4f, size * (0.6f + 0.5f * rnd.nextFloat()), Color.WHITE, 0.55f) }
        }
        // Hills.
        val far = Path()
        val near = Path()
        far.moveTo(0f, h.toFloat()); near.moveTo(0f, h.toFloat())
        var x = 0f
        while (x <= w + 8) {
            far.lineTo(x, h * 0.66f + h * 0.04f * sin(x / w * 2.2f * PI + 0.5).toFloat())
            near.lineTo(x, h * 0.76f + h * 0.05f * sin(x / w * 1.6f * PI + 3.4).toFloat())
            x += 8f
        }
        far.lineTo(w.toFloat(), h.toFloat()); near.lineTo(w.toFloat(), h.toFloat())
        c.drawPath(far, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, h * 0.62f, 0f, h * 0.8f, 0xFF7FD36A.toInt(), 0xFF3F9E3C.toInt(), Shader.TileMode.CLAMP) })
        c.drawPath(near, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, h * 0.72f, 0f, h.toFloat(), 0xFF58C23F.toInt(), 0xFF1F7A2A.toInt(), Shader.TileMode.CLAMP) })
        bubbles(c, w, h, 14, 4, w * 0.012f, w * 0.06f, 1.2f)
    }

    private fun sunset(c: Canvas, w: Int, h: Int) {
        vertical(c, w, h, 0xFF2B1B54.toInt(), 0xFF8A3A86.toInt(), 0xFFE0577A.toInt(), 0xFFFF9A5C.toInt(), 0xFFFFD27A.toInt())
        glow(c, w * 0.5f, h * 0.62f, w * 0.9f, 0xFFFFE9A8.toInt(), 0.85f)
        bokeh(c, w, h, intArrayOf(0xFFFFD6A0.toInt(), 0xFFFF9EC0.toInt(), 0xFFFFFFFF.toInt()), 26, 6, w * 0.04f, w * 0.20f, 0.30f)
        wave(c, w, h * 0.78f, h * 0.012f, w * 1.0f, 1.0f, h, Lx.alpha(0xFF3A1550.toInt(), 0.55f), Lx.alpha(0xFFFFD9A0.toInt(), 0.45f))
        wave(c, w, h * 0.86f, h * 0.014f, w * 0.8f, 3.0f, h, Lx.alpha(0xFF24103C.toInt(), 0.75f))
        bubbles(c, w, h, 10, 12, w * 0.01f, w * 0.05f)
    }

    private fun aurora(c: Canvas, w: Int, h: Int) {
        vertical(c, w, h, 0xFF040A18.toInt(), 0xFF0A1B36.toInt(), 0xFF0C2A44.toInt())
        val rnd = Random(9)
        repeat(90) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Lx.alpha(Color.WHITE, 0.25f + 0.6f * rnd.nextFloat()) }
            c.drawCircle(w * rnd.nextFloat(), h * 0.6f * rnd.nextFloat(), max(1f, w * 0.0025f * rnd.nextFloat() * 2), p)
        }
        val colors = intArrayOf(0xFF3CF2B0.toInt(), 0xFF2CC7F2.toInt(), 0xFF9B6BFF.toInt())
        for (band in 0 until 3) {
            val path = Path()
            val baseY = h * (0.22f + 0.12f * band)
            path.moveTo(0f, baseY)
            var x = 0f
            while (x <= w + 8) {
                path.lineTo(x, baseY + h * 0.07f * sin(x / w * 2.4f * PI + band * 1.7).toFloat() + h * 0.02f * sin(x / w * 7f * PI + band).toFloat())
                x += 8f
            }
            for (layer in 0 until 6) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = h * (0.05f - layer * 0.007f)
                    color = Lx.alpha(colors[band], 0.10f + layer * 0.03f)
                    maskFilter = BlurMaskFilter(h * 0.03f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawPath(path, p)
            }
        }
        wave(c, w, h * 0.90f, h * 0.012f, w * 1.2f, 0.3f, h, Lx.alpha(Color.BLACK, 0.55f))
    }

    private fun deep(c: Canvas, w: Int, h: Int) {
        vertical(c, w, h, 0xFF0A4C7A.toInt(), 0xFF053457.toInt(), 0xFF031E3A.toInt(), 0xFF020F22.toInt())
        glow(c, w * 0.3f, -h * 0.05f, w * 1.0f, 0xFF9FE8FF.toInt(), 0.45f)
        rays(c, w, h, w * 0.3f, 8, 17, 0.16f)
        bubbles(c, w, h, 26, 31, w * 0.008f, w * 0.06f, 0.9f)
        val rnd = Random(4)
        repeat(40) { glow(c, w * rnd.nextFloat(), h * (0.35f + 0.6f * rnd.nextFloat()), w * (0.01f + 0.025f * rnd.nextFloat()), 0xFF6CF2E0.toInt(), 0.25f) }
    }
}
