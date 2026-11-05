package com.driftdroid.android.ui

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The player's own themes: a name, a look, colours and the feel settings, saved on the phone and
 * exportable to a small file (and importable from one) to share or move to another phone. A theme
 * file can carry the background picture when it is small; animations and videos are not included.
 */
internal object ThemeStore {
    class Saved(
        val id: String,
        val name: String,
        val look: Look,
        val texture: Texture,
        val top: Int,
        val bottom: Int,
        val accent: Int,
        val angle: Float,
        val panelAlpha: Float,
        val cornerScale: Float,
        val dim: Float,
        val portrait: Theme.Framing,
        val landscape: Theme.Framing,
        /** The background kept with this theme: "image", "anim", "video" or null for none. */
        val media: String? = null,
        /** Brightness of that background (for the text colour), when known. */
        val luma: Float? = null,
        /** "checker" or "none". */
        val pattern: String = "none",
    ) {
        val mediaKind: BackgroundMedia.Kind
            get() =
                when (media) {
                    "image" -> BackgroundMedia.Kind.IMAGE
                    "anim" -> BackgroundMedia.Kind.ANIM
                    "video" -> BackgroundMedia.Kind.VIDEO
                    else -> BackgroundMedia.Kind.NONE
                }
    }

    private const val FORMAT = "driftdroid-theme"
    private const val MAX_NAME = 40
    private const val MAX_EMBEDDED_IMAGE = 2_500_000
    private const val MAX_IMPORT_BYTES = 6_000_000
    private const val MAX_KEPT_MEDIA = 64L shl 20
    const val MAX_THEMES = 40

    private fun file(context: Context) = File(context.filesDir, "themes.json")

    private fun mediaDir(context: Context) = File(context.filesDir, "themes").also { it.mkdirs() }

    /** Where [saved]'s own background is kept. */
    fun mediaFile(context: Context, saved: Saved): File = File(mediaDir(context), "${saved.id}.${BackgroundMedia.extensionOf(saved.mediaKind)}")

    fun list(context: Context): List<Saved> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNull { runCatching { fromJson(array.getJSONObject(it), fresh = false) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, themes: List<Saved>) {
        val array = JSONArray()
        themes.forEach { array.put(toJson(it)) }
        val temporary = File(context.filesDir, "themes.json.saving")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(file(context))) {
            temporary.delete()
            throw IOException("couldn't save the theme")
        }
    }

    /** Saves what is on screen (or these pending values) under [name]. */
    fun save(context: Context, name: String, look: Look, texture: Texture, top: Int, bottom: Int, accent: Int, angle: Float, panelAlpha: Float, cornerScale: Float, pattern: String = Theme.pattern): Saved {
        val all = list(context)
        if (all.size >= MAX_THEMES) throw IOException("that is the most themes you can keep ($MAX_THEMES) - delete one first")
        val id = UUID.randomUUID().toString()
        // Keep the current background (picture, GIF or video) with the theme, unless it is very large.
        val kind = BackgroundMedia.kind(context)
        val current = BackgroundMedia.currentFile(context)
        val keep = kind != BackgroundMedia.Kind.NONE && current != null && current.length() <= MAX_KEPT_MEDIA
        val media =
            if (keep) {
                when (kind) {
                    BackgroundMedia.Kind.IMAGE -> "image"
                    BackgroundMedia.Kind.ANIM -> "anim"
                    else -> "video"
                }
            } else {
                null
            }
        val saved =
            Saved(
                id, cleanName(name), look, texture, top, bottom, accent, angle, panelAlpha, cornerScale, Theme.dim,
                Theme.portrait.copy(), Theme.landscape.copy(), media, if (keep) BackgroundMedia.storedBrightness(context) else null, pattern,
            )
        if (keep && current != null) current.copyTo(mediaFile(context, saved), overwrite = true)
        write(context, all + saved)
        return saved
    }

    fun delete(context: Context, id: String) {
        val all = list(context)
        all.firstOrNull { it.id == id }?.let { runCatching { mediaFile(context, it).delete() } }
        write(context, all.filter { it.id != id })
    }

    fun apply(context: Context, saved: Saved) {
        // A saved theme is the whole look: its own background comes back with it (or none, if it had none).
        val file = mediaFile(context, saved)
        if (saved.media != null && file.isFile) {
            BackgroundMedia.restoreFrom(context, file, saved.mediaKind, saved.luma)
        } else {
            BackgroundMedia.remove(context)
        }
        Theme.applyValues(context, saved.look, saved.texture, saved.top, saved.bottom, saved.accent, saved.angle, saved.panelAlpha, saved.cornerScale, saved.dim, saved.portrait, saved.landscape, saved.pattern)
    }

    // --- Files ---

    fun export(context: Context, saved: Saved): String {
        val json = JSONObject(exportText(saved))
        val image = mediaFile(context, saved)
        if (saved.media == "image" && image.isFile && image.length() in 1..MAX_EMBEDDED_IMAGE) {
            json.put("image", Base64.encodeToString(image.readBytes(), Base64.NO_WRAP))
        }
        return json.toString(1)
    }

    /** True when exporting [saved] would include its background picture (GIFs and videos are never included). */
    fun exportIncludesPicture(context: Context, saved: Saved): Boolean =
        saved.media == "image" && mediaFile(context, saved).length() in 1..MAX_EMBEDDED_IMAGE

    /** A theme read from a file: the settings, and the embedded picture (base64) if the file carries one. */
    class Parsed(val theme: Saved, val image: String)

    /** Checks and reads a theme file's text. Throws [IOException] with a plain message if it is not a theme. */
    fun parse(text: String): Parsed {
        if (text.length > MAX_IMPORT_BYTES) throw IOException("that file is too big to be a theme")
        val json =
            try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("that isn't a DriftDroid theme file")
            }
        if (json.optString("format") != FORMAT) throw IOException("that isn't a DriftDroid theme file")
        return Parsed(fromJson(json, fresh = true), json.optString("image", ""))
    }

    /** Adds the theme in [text] to the saved ones. Throws [IOException] with a plain message if it is not one. */
    fun import(context: Context, text: String): Saved {
        val parsed = parse(text)
        val all = list(context)
        if (all.size >= MAX_THEMES) throw IOException("that is the most themes you can keep ($MAX_THEMES) - delete one first")
        var theme = parsed.theme
        if (parsed.image.isNotEmpty()) {
            val bytes = runCatching { Base64.decode(parsed.image, Base64.DEFAULT) }.getOrNull() ?: throw IOException("the picture in that theme is damaged")
            if (bytes.size > MAX_EMBEDDED_IMAGE * 2) throw IOException("the picture in that theme is too big")
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("the picture in that theme can't be read")
            theme = Saved(theme.id, theme.name, theme.look, theme.texture, theme.top, theme.bottom, theme.accent, theme.angle, theme.panelAlpha, theme.cornerScale, theme.dim, theme.portrait, theme.landscape, "image", theme.luma, theme.pattern)
            mediaFile(context, theme).writeBytes(bytes)
        }
        // A file cannot claim a background it does not carry.
        if (parsed.image.isEmpty() && theme.media != null) {
            theme = Saved(theme.id, theme.name, theme.look, theme.texture, theme.top, theme.bottom, theme.accent, theme.angle, theme.panelAlpha, theme.cornerScale, theme.dim, theme.portrait, theme.landscape, null, null, theme.pattern)
        }
        write(context, all + theme)
        return theme
    }

    /** The text of a theme file for [saved] (no picture): what [export] writes before it adds one. */
    fun exportText(saved: Saved): String {
        val json = toJson(saved)
        json.put("format", FORMAT).put("version", 1)
        json.remove("id")
        json.remove("media")
        return json.toString(1)
    }

    // --- JSON ---

    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)

    private fun parse(text: String, fallback: Int): Int {
        val digits = text.trim().removePrefix("#")
        if (digits.length != 6 || !digits.all { it in "0123456789abcdefABCDEF" }) return fallback
        return digits.toInt(16) or 0xFF000000.toInt()
    }

    private fun r3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

    private fun framing(f: Theme.Framing) = JSONArray().put(r3(f.zoom)).put(r3(f.cx)).put(r3(f.cy))

    private fun framingOf(a: JSONArray?): Theme.Framing =
        if (a == null || a.length() < 3) {
            Theme.Framing()
        } else {
            Theme.Framing(a.optDouble(0, 1.0).toFloat().coerceIn(0.2f, 6f), a.optDouble(1, 0.5).toFloat().coerceIn(0f, 1f), a.optDouble(2, 0.5).toFloat().coerceIn(0f, 1f))
        }

    private fun toJson(s: Saved): JSONObject =
        JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("look", s.look.id)
            .put("texture", s.texture.id)
            .put("top", hex(s.top))
            .put("bottom", hex(s.bottom))
            .put("accent", hex(s.accent))
            .put("angle", r3(s.angle))
            .put("panelAlpha", r3(s.panelAlpha))
            .put("cornerScale", r3(s.cornerScale))
            .put("dim", r3(s.dim))
            .put("portrait", framing(s.portrait))
            .put("landscape", framing(s.landscape))
            .put("pattern", s.pattern)
            .also { j ->
                if (s.media != null) j.put("media", s.media)
                if (s.luma != null) j.put("luma", r3(s.luma))
            }

    private fun fromJson(j: JSONObject, fresh: Boolean): Saved =
        Saved(
            id = if (fresh) UUID.randomUUID().toString() else j.getString("id"),
            name = cleanName(j.optString("name", "Theme")),
            look = Look.of(j.optString("look")),
            texture = Texture.of(j.optString("texture")),
            top = parse(j.optString("top"), 0xFF0C0E11.toInt()),
            bottom = parse(j.optString("bottom"), 0xFF0C0E11.toInt()),
            accent = parse(j.optString("accent"), 0xFF3DDC84.toInt()),
            angle = j.optDouble("angle", 90.0).toFloat().coerceIn(0f, 360f),
            panelAlpha = j.optDouble("panelAlpha", 0.30).toFloat().coerceIn(0.12f, 0.85f),
            cornerScale = j.optDouble("cornerScale", 1.0).toFloat().coerceIn(0.5f, 1.8f),
            dim = j.optDouble("dim", 0.35).toFloat().coerceIn(0f, Theme.MAX_DIM),
            portrait = framingOf(j.optJSONArray("portrait")),
            landscape = framingOf(j.optJSONArray("landscape")),
            media = j.optString("media", "").takeIf { it == "image" || it == "anim" || it == "video" },
            luma = if (j.has("luma")) j.optDouble("luma", 0.3).toFloat().coerceIn(0f, 1f) else null,
            pattern = j.optString("pattern", "none").takeIf { it == "checker" } ?: "none",
        )

    private fun cleanName(name: String): String {
        val cleaned = name.filter { it >= ' ' && it != '\u007f' }.trim().take(MAX_NAME)
        return cleaned.ifEmpty { "My theme" }
    }
}
