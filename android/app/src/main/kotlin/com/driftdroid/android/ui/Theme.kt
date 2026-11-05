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
    class Preset(val id: String, val name: String, val top: Int, val bottom: Int, val accent: Int)

    val PRESETS =
        listOf(
            Preset("pit_lane", "Pit lane", 0xFF0C0E11.toInt(), 0xFF0C0E11.toInt(), 0xFF3DDC84.toInt()),
            Preset("midnight", "Midnight", 0xFF0B1226.toInt(), 0xFF16284A.toInt(), 0xFF5CC8FF.toInt()),
            Preset("sunset", "Sunset", 0xFF1E0F1F.toInt(), 0xFF40182A.toInt(), 0xFFFF8A4C.toInt()),
            Preset("forest", "Forest", 0xFF0A1410.toInt(), 0xFF133326.toInt(), 0xFF7BE495.toInt()),
            Preset("grape", "Grape", 0xFF150F24.toInt(), 0xFF2D1B50.toInt(), 0xFFB69CFF.toInt()),
            Preset("graphite", "Graphite", 0xFF141414.toInt(), 0xFF2A2A2A.toInt(), 0xFFFFC94A.toInt()),
        )

    const val CUSTOM = "custom"
    const val MAX_IMAGE_SIDE = 1600
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
    @Volatile var dim: Float = 0.35f
        private set

    private var bitmap: Bitmap? = null
    private var bitmapLoaded = false

    /** True when everything is the shipped look, so the original constants are used as they were. */
    private val isOriginal: Boolean
        get() = presetId == "pit_lane"

    val bg: Int get() = if (isOriginal) PIT_BG else top
    val bar: Int get() = if (isOriginal) PIT_BAR else tone(0.02f)
    val surfaceLo: Int get() = if (isOriginal) PIT_SURFACE_LO else tone(0.03f)
    val surface: Int get() = if (isOriginal) PIT_SURFACE else tone(0.055f)
    val surfaceHi: Int get() = if (isOriginal) PIT_SURFACE_HI else tone(0.10f)
    val line: Int get() = if (isOriginal) PIT_LINE else tone(0.16f)
    val accent: Int get() = accentColor
    val accentText: Int
        get() = if (isOriginal) PIT_ACCENT_TEXT else if (luminance(accentColor) > 0.45f) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()

    /** The backdrop colour mixed towards white, for panels a step lighter than it. */
    private fun tone(amount: Float): Int = mix(average(top, bottom), Color.WHITE, amount)

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
        bitmapLoaded = false
        bitmap = null
    }

    /** Switches to a preset. */
    fun usePreset(context: Context, preset: Preset) {
        presetId = preset.id
        top = preset.top
        bottom = preset.bottom
        accentColor = preset.accent
        save(context)
    }

    /** Switches to custom colours (backdrop colours are kept dark enough for the text). */
    fun useCustom(context: Context, top: Int, bottom: Int, accent: Int, angle: Float) {
        presetId = CUSTOM
        this.top = darken(top)
        this.bottom = darken(bottom)
        accentColor = accent or 0xFF000000.toInt()
        this.angle = angle
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
            .apply()
    }

    fun reset(context: Context) {
        usePreset(context, PRESETS[0])
        removeImage(context)
        dim = 0.35f
        angle = 90f
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
     * long side (decoded with a sample size first, so a huge photo cannot exhaust memory). Throws
     * [java.io.IOException] with a plain message when the file is not a readable picture.
     */
    @Synchronized
    fun importImage(context: Context, uri: android.net.Uri) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw java.io.IOException("couldn't open the picture")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("that isn't a picture")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE * 2) sample *= 2
        val decoded =
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw java.io.IOException("couldn't read the picture")
        val longSide = max(decoded.width, decoded.height)
        val scaled =
            if (longSide > MAX_IMAGE_SIDE) {
                val f = MAX_IMAGE_SIDE.toFloat() / longSide
                Bitmap.createScaledBitmap(decoded, (decoded.width * f).toInt().coerceAtLeast(1), (decoded.height * f).toInt().coerceAtLeast(1), true)
            } else {
                decoded
            }
        val target = imageFile(context)
        val temporary = File(context.filesDir, "$FILE_NAME.saving")
        temporary.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw java.io.IOException("couldn't save the picture")
        }
        if (scaled !== decoded) decoded.recycle()
        bitmap = scaled
        bitmapLoaded = true
    }

    // --- Colour helpers (also used by the Appearance sheet) ---

    fun hue(color: Int): Float = FloatArray(3).also { Color.colorToHSV(color, it) }[0]

    fun value(color: Int): Float = FloatArray(3).also { Color.colorToHSV(color, it) }[2]

    fun fromHsv(hue: Float, saturation: Float, value: Float): Int =
        Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))

    private fun darken(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = min(hsv[2], 0.35f)
        return Color.HSVToColor(hsv)
    }

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
