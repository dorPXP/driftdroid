package com.driftdroid.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Translations for the app's own screens. The same JSON files the game's menus use (i18n/<code>.json,
 * copied into the assets by tools/i18n/gen.py): the English text of a string is its key, and a missing
 * entry shows the English text. The player's choice is the same `language.cfg` the game reads
 * ("auto" or a language code); "auto" follows the phone's language.
 */
internal object I18n {
    private var table: Map<String, String> = emptyMap()
    var code: String = "en"
        private set
    private var ready = false

    /** Loads the chosen language (once per process; call again after the choice changes). */
    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        val choice = savedChoice(app)
        val resolved = if (choice == "auto" || !hasLanguage(app, choice)) systemLanguage(app) else choice
        code = resolved
        table = load(app, resolved)
        ready = true
    }

    fun t(text: String): String = table[text] ?: text

    /** Language codes with their own names, English first. */
    fun languages(context: Context): List<Pair<String, String>> {
        val assets = context.applicationContext.assets
        val names = assets.list("i18n")?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") } ?: return listOf("en" to "English")
        val order = listOf("en", "es-419", "es-ES", "pt-BR", "fr", "de", "it", "ja", "ko", "zh-Hans", "ru", "tr", "nl", "pl")
        return (order.filter { it in names } + names.filter { it !in order }.sorted()).map { it to nameOf(assets, it) }
    }

    fun savedChoice(context: Context): String {
        val file = choiceFile(context)
        return runCatching { file.readText().trim().ifEmpty { "auto" } }.getOrDefault("auto")
    }

    fun saveChoice(context: Context, choice: String) {
        val file = choiceFile(context)
        file.parentFile?.mkdirs()
        file.writeText(choice + "\n")
    }

    /** The language code the game should use for "auto" on this phone. */
    fun systemLanguage(context: Context): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val language = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.uppercase(Locale.ROOT)
        val script = locale.script
        val wanted =
            when (language) {
                "es" -> if (country == "ES") "es-ES" else "es-419"
                "pt" -> "pt-BR"
                "zh" -> if (script.equals("Hant", true) || country == "TW" || country == "HK" || country == "MO") "en" else "zh-Hans"
                "en", "fr", "de", "it", "ja", "ko", "ru", "tr", "nl", "pl" -> language
                else -> "en"
            }
        return if (hasLanguage(context, wanted)) wanted else "en"
    }

    private fun choiceFile(context: Context) = File(context.filesDir, "WiiCompiled/language.cfg")

    private fun hasLanguage(context: Context, code: String): Boolean =
        runCatching { context.assets.list("i18n")?.contains("$code.json") == true }.getOrDefault(false)

    private fun nameOf(assets: android.content.res.AssetManager, code: String): String =
        runCatching {
            assets.open("i18n/$code.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)).optString("name", code) }
        }.getOrDefault(code)

    private fun load(context: Context, code: String): Map<String, String> =
        runCatching {
            context.assets.open("i18n/$code.json").use { stream ->
                val strings = JSONObject(stream.readBytes().toString(Charsets.UTF_8)).optJSONObject("strings") ?: return emptyMap()
                val map = HashMap<String, String>(strings.length() * 2)
                for (key in strings.keys()) {
                    map[key] = strings.getString(key)
                }
                map
            }
        }.getOrDefault(emptyMap())
}

/** The translation of an English string (the same text when there is none). */
internal fun T(text: String): String = I18n.t(text)
