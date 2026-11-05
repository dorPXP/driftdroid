package com.driftdroid.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The launcher's colours and background, chosen by the player (Settings > Appearance).
 *
 * [Lx] reads its backdrop, panel and accent colours from here. The default preset reproduces the
 * original "pit lane" look exactly; the others and the custom colours derive their panel tones from
 * the backdrop so text stays readable (backdrops are kept dark). A change is saved and the launcher
 * is recreated to wear it, since views take their colours when they are built.
 *
 * An optional background picture is copied into the app's own storage, scaled down to a screen-sized
 * JPEG, and drawn over the gradient with a dim layer on top.
 */
internal object Theme {
    class Preset(
        val id: String,
        val name: String,
        val look: Look,
        val wallpaper: Wallpaper?,
        val top: Int,
        val bottom: Int,
        val accent: Int,
    )

    private fun flat(id: String, name: String, top: Long, bottom: Long, accent: Long) =
        Preset(id, name, Look.FLAT, null, top.toInt(), bottom.toInt(), accent.toInt())

    val PRESETS =
        listOf(
            flat("pit_lane", "Pit lane", 0xFF0C0E11, 0xFF0C0E11, 0xFF3DDC84),
            Preset("aqua_glass", "Aqua glass", Look.GLASS, Wallpaper.AQUA, 0xFF0F78C8.toInt(), 0xFF07306A.toInt(), 0xFF3DF2E0.toInt()),
            Preset("frutiger", "Frutiger Aero", Look.AERO, Wallpaper.MEADOW, 0xFF7CCBFF.toInt(), 0xFFCDEEFF.toInt(), 0xFF22A7F0.toInt()),
            Preset("deep_glass", "Deep sea", Look.GLASS, Wallpaper.DEEP, 0xFF053457.toInt(), 0xFF020F22.toInt(), 0xFF5CE1E6.toInt()),
            Preset("aurora_glass", "Aurora", Look.GLASS, Wallpaper.AURORA, 0xFF0A1B36.toInt(), 0xFF0C2A44.toInt(), 0xFF9B6BFF.toInt()),
            Preset("sunset_glass", "Sunset glass", Look.GLASS, Wallpaper.SUNSET, 0xFF8A3A86.toInt(), 0xFFFF9A5C.toInt(), 0xFFFFB36B.toInt()),
            Preset("bubble_pop", "Bubble pop", Look.AERO, Wallpaper.BUBBLES, 0xFF22D3C5.toInt(), 0xFF6A5BEA.toInt(), 0xFFFFD84D.toInt()),
            Preset("steel", "Brushed steel", Look.SKEUO, null, 0xFF2C3036.toInt(), 0xFF454B54.toInt(), 0xFFFF9F2E.toInt()),
            Preset("leather", "Leather", Look.SKEUO, null, 0xFF3B2A20.toInt(), 0xFF5A3E2E.toInt(), 0xFFE8B04A.toInt()),
            flat("midnight", "Midnight", 0xFF0B1226, 0xFF16284A, 0xFF5CC8FF),
            flat("sunset", "Sunset", 0xFF1E0F1F, 0xFF40182A, 0xFFFF8A4C),
            flat("forest", "Forest", 0xFF0A1410, 0xFF133326, 0xFF7BE495),
            flat("grape", "Grape", 0xFF150F24, 0xFF2D1B50, 0xFFB69CFF),
            flat("graphite", "Graphite", 0xFF141414, 0xFF2A2A2A, 0xFFFFC94A),
        )

    const val CUSTOM = "custom"
    const val MAX_IMAGE_SIDE = 2560
    const val MAX_DIM = 0.8f

    private const val PREFS = "launcher"
    private const val FILE_NAME = "launcher_background.jpg"

    // The original look, kept exactly (Lx's former constants) so the default theme changes nothing.
    private const val PIT_BG = 0xFF0C0E11.toInt()
    private const val PIT_BAR = 0xFF111418.toInt()
    private const val PIT_SURFACE_LO = 0xFF121519.toInt()
    private const val PIT_SURFACE = 0xFF171A1F.toInt()
    private const val PIT_SURFACE_HI = 0xFF20242B.toInt()
    private const val PIT_LINE = 0xFF2A2F37.toInt()
    private const val PIT_ACCENT_TEXT = 0xFF04210F.toInt()

    @Volatile var presetId: String = "pit_lane"
        private set
    @Volatile var top: Int = PRESETS[0].top
        private set
    @Volatile var bottom: Int = PRESETS[0].bottom
        private set
    @Volatile var accentColor: Int = PRESETS[0].accent
        private set
    @Volatile var angle: Float = 90f
        private set
    @Volatile var look: Look = Look.FLAT
        private set
    @Volatile var wallpaper: Wallpaper? = null
        private set
    /** How see-through the neutral surfaces are in the glass looks (0.12 - 0.85). */
    @Volatile var panelAlpha: Float = 0.30f
        private set
    /** Scales every corner radius (0.5 - 1.8). */
    @Volatile var cornerScale: Float = 1f
        private set
    /** Scales all text on top of the system font size (0.85 - 1.4). */
    @Volatile var textScale: Float = 1f
        private set
    @Volatile var dim: Float = 0.35f
        private set

    /** How the picture sits on the screen: [zoom] 1 = fills it (cover); [cx], [cy] = the point of the picture in the middle. */
    class Framing(var zoom: Float = 1f, var cx: Float = 0.5f, var cy: Float = 0.5f) {
        fun copy() = Framing(zoom, cx, cy)
    }

    val portrait = Framing()
    val landscape = Framing()

    /** Where the picture is drawn on a [w] x [h] screen with [f]; [out] receives the rectangle. */
    fun place(imageW: Int, imageH: Int, w: Float, h: Float, f: Framing, out: android.graphics.RectF) {
        val cover = max(w / imageW, h / imageH)
        val scale = cover * f.zoom
        val dw = imageW * scale
        val dh = imageH * scale
        val left = w / 2f - f.cx * dw
        val top = h / 2f - f.cy * dh
        out.set(left, top, left + dw, top + dh)
    }

    fun framingFor(width: Int, height: Int): Framing = if (width >= height) landscape else portrait

    fun saveFraming(context: Context) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for ((name, f) in listOf("p" to portrait, "l" to landscape)) {
            e.putFloat("bg_${name}_zoom", f.zoom).putFloat("bg_${name}_cx", f.cx).putFloat("bg_${name}_cy", f.cy)
        }
        e.apply()
    }

    private fun loadFraming(p: android.content.SharedPreferences) {
        for ((name, f) in listOf("p" to portrait, "l" to landscape)) {
            f.zoom = p.getFloat("bg_${name}_zoom", 1f).coerceIn(0.2f, 6f)
            f.cx = p.getFloat("bg_${name}_cx", 0.5f).coerceIn(0f, 1f)
            f.cy = p.getFloat("bg_${name}_cy", 0.5f).coerceIn(0f, 1f)
        }
    }

    private var bitmap: Bitmap? = null
    private var bitmapLoaded = false

    /** True when everything is the shipped look, so the original constants are used as they were. */
    private val isOriginal: Boolean
        get() = presetId == "pit_lane" && look == Look.FLAT && wallpaper == null

    val bg: Int get() = if (isOriginal) PIT_BG else top
    val bar: Int
        get() =
            when {
                isOriginal -> PIT_BAR
                look == Look.GLASS || look == Look.AERO -> Lx.alpha(if (isLight) Color.WHITE else 0xFF000000.toInt(), if (isLight) 0.50f else 0.32f)
                else -> tone(0.02f)
            }
    val surfaceLo: Int get() = if (isOriginal) PIT_SURFACE_LO else tone(0.03f)
    val surface: Int get() = if (isOriginal) PIT_SURFACE else tone(0.055f)
    val surfaceHi: Int get() = if (isOriginal) PIT_SURFACE_HI else tone(0.10f)
    val line: Int get() = if (isOriginal) PIT_LINE else tone(0.16f)
    val accent: Int get() = accentColor
    val accentText: Int
        get() = if (isOriginal) PIT_ACCENT_TEXT else if (luminance(accentColor) > 0.45f) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()

    /** True when the backdrop is light enough that the text has to be dark. */
    val isLight: Boolean
        get() = !isOriginal && (wallpaper?.light ?: (luminance(average(top, bottom)) > 0.5f))

    val text: Int get() = if (isLight) 0xFF14171C.toInt() else 0xFFF1F3F6.toInt()
    val muted: Int get() = if (isLight) 0xFF4A515B.toInt() else 0xFFA3ABB7.toInt()
    val faint: Int get() = if (isLight) 0xFF6A727E.toInt() else 0xFF77808C.toInt()

    /** The backdrop colour mixed towards white (towards black on a light backdrop): panels a step off it. */
    private fun tone(amount: Float): Int = mix(average(top, bottom), if (isLight) Color.BLACK else Color.WHITE, if (isLight) amount * 0.8f else amount)

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString("theme_preset", "pit_lane") ?: "pit_lane"
        val preset = PRESETS.firstOrNull { it.id == id }
        presetId = if (preset != null || id == CUSTOM) id else "pit_lane"
        val base = preset ?: PRESETS[0]
        top = p.getInt("theme_top", base.top)
        bottom = p.getInt("theme_bottom", base.bottom)
        accentColor = p.getInt("theme_accent", base.accent)
        angle = p.getFloat("theme_angle", 90f)
        dim = p.getFloat("theme_dim", 0.35f).coerceIn(0f, MAX_DIM)
        look = Look.of(p.getString("theme_look", base.look.id))
        wallpaper = if (p.contains("theme_wallpaper")) Wallpaper.of(p.getString("theme_wallpaper", null)) else base.wallpaper
        panelAlpha = p.getFloat("theme_panel_alpha", 0.30f).coerceIn(0.12f, 0.85f)
        cornerScale = p.getFloat("theme_corner_scale", 1f).coerceIn(0.5f, 1.8f)
        textScale = p.getFloat("theme_text_scale", 1f).coerceIn(0.85f, 1.4f)
        loadFraming(p)
        bitmapLoaded = false
        bitmap = null
    }

    /** Switches to a preset. */
    fun usePreset(context: Context, preset: Preset) {
        presetId = preset.id
        look = preset.look
        wallpaper = preset.wallpaper
        top = preset.top
        bottom = preset.bottom
        accentColor = preset.accent
        save(context)
    }

    /** Switches to custom colours (backdrop colours are kept dark enough for the text). */
    fun useCustom(context: Context, top: Int, bottom: Int, accent: Int, angle: Float, look: Look, wallpaper: Wallpaper?) {
        presetId = CUSTOM
        this.look = look
        this.wallpaper = wallpaper
        this.top = top or 0xFF000000.toInt()
        this.bottom = bottom or 0xFF000000.toInt()
        accentColor = accent or 0xFF000000.toInt()
        this.angle = angle
        save(context)
    }

    fun setFeel(context: Context, panelAlpha: Float, cornerScale: Float, textScale: Float) {
        this.panelAlpha = panelAlpha.coerceIn(0.12f, 0.85f)
        this.cornerScale = cornerScale.coerceIn(0.5f, 1.8f)
        this.textScale = textScale.coerceIn(0.85f, 1.4f)
        save(context)
    }

    fun setDim(context: Context, value: Float) {
        dim = value.coerceIn(0f, MAX_DIM)
        save(context)
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme_preset", presetId)
            .putInt("theme_top", top)
            .putInt("theme_bottom", bottom)
            .putInt("theme_accent", accentColor)
            .putFloat("theme_angle", angle)
            .putFloat("theme_dim", dim)
            .putString("theme_look", look.id)
            .putString("theme_wallpaper", wallpaper?.id ?: "none")
            .putFloat("theme_panel_alpha", panelAlpha)
            .putFloat("theme_corner_scale", cornerScale)
            .putFloat("theme_text_scale", textScale)
            .apply()
    }

    fun reset(context: Context) {
        usePreset(context, PRESETS[0])
        removeImage(context)
        portrait.zoom = 1f; portrait.cx = 0.5f; portrait.cy = 0.5f
        landscape.zoom = 1f; landscape.cx = 0.5f; landscape.cy = 0.5f
        saveFraming(context)
        dim = 0.35f
        angle = 90f
        panelAlpha = 0.30f
        cornerScale = 1f
        textScale = 1f
        save(context)
    }

    // --- Background picture ---

    fun imageFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasImage(context: Context): Boolean = imageFile(context).isFile

    /** The saved picture, decoded once; null when there is none. */
    @Synchronized
    fun image(context: Context): Bitmap? {
        if (!bitmapLoaded) {
            bitmapLoaded = true
            bitmap = imageFile(context).takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        }
        return bitmap
    }

    @Synchronized
    fun removeImage(context: Context) {
        imageFile(context).delete()
        bitmap = null
        bitmapLoaded = true
    }

    /**
     * Copies a picked picture into the app's storage as a JPEG no larger than [MAX_IMAGE_SIDE] on its
     * long side. Decoding goes through [android.graphics.ImageDecoder] (any format the phone can show -
     * JPEG, PNG, WebP, HEIC, AVIF, and the first frame of a GIF - with the photo's rotation applied and
     * the size reduced while decoding, so a huge photo cannot exhaust memory). Throws
     * [java.io.IOException] with a plain message when the file is not a readable picture.
     */
    @Synchronized
    fun importImage(context: Context, uri: android.net.Uri) {
        val decoded: Bitmap =
            try {
                decodeWithImageDecoder(context, uri)
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw java.io.IOException("the picture is too large for this phone")
                decodeWithBitmapFactory(context, uri) ?: throw java.io.IOException("this phone can't read that kind of picture")
            }
        val target = imageFile(context)
        val temporary = File(context.filesDir, "$FILE_NAME.saving")
        temporary.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw java.io.IOException("couldn't save the picture")
        }
        bitmap = decoded
        bitmapLoaded = true
        portrait.zoom = 1f; portrait.cx = 0.5f; portrait.cy = 0.5f
        landscape.zoom = 1f; landscape.cx = 0.5f; landscape.cy = 0.5f
        saveFraming(context)
    }

    private fun decodeWithImageDecoder(context: Context, uri: android.net.Uri): Bitmap {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longSide = max(info.size.width, info.size.height)
            if (longSide > MAX_IMAGE_SIDE) {
                val f = MAX_IMAGE_SIDE.toFloat() / longSide
                decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
            }
        }
    }

    private fun decodeWithBitmapFactory(context: Context, uri: android.net.Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE * 2) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    // --- Colour helpers (also used by the Appearance sheet) ---

    fun hue(color: Int): Float = FloatArray(3).also { Color.colorToHSV(color, it) }[0]

    fun value(color: Int): Float = FloatArray(3).also { Color.colorToHSV(color, it) }[2]

    fun fromHsv(hue: Float, saturation: Float, value: Float): Int =
        Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))

    private fun average(a: Int, b: Int): Int = mix(a, b, 0.5f)

    private fun mix(a: Int, b: Int, amount: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * amount).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f
}
