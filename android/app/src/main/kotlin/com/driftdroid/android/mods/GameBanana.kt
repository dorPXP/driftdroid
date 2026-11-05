package com.driftdroid.android.mods

import com.driftdroid.android.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * GameBanana's public API (apiv12) for Mario Kart Wii (game 5896): the mod browser's listings,
 * search and mod pages. The same endpoints WheelWizard's mod browser uses (IGameBananaApi.cs).
 */
internal object GameBanana {
    const val GAME_ID = 5896
    private const val API = "https://gamebanana.com/apiv12"
    // Downloads redirect to files.gamebanana.com or numbered caches (filecache53.gamebanana.com...).
    val HOSTS = setOf("gamebanana.com", "*.gamebanana.com")
    const val PAGE_SIZE = 20

    /** A browser tab. [rootName] is how search results name the same category. */
    class Category(val id: Int?, val label: String, val rootName: String?)

    // Mario Kart Wii's category tree: gamebanana.com/games/cattree/5896
    val CATEGORIES =
        listOf(
            Category(null, "All", null),
            Category(10080, "Skins", "Skins"),
            Category(799, "Tracks", "Tracks"),
            Category(2083, "UI", "UI"),
            Category(4152, "Other", "Other/Misc"),
        )

    class ModSummary(
        val id: Int,
        val name: String,
        val author: String,
        val category: String,
        val thumbnailUrl: String?,
        /** The same first screenshot at 530px wide, for big cards. */
        val previewUrl: String?,
        val likes: Int,
        val views: Int,
        val addedAt: Long,
    ) {
        val profileUrl: String get() = "https://gamebanana.com/mods/$id"
    }

    class ListPage(val mods: List<ModSummary>, val total: Int, val complete: Boolean)

    class ModFile(val id: Int, val name: String, val size: Long, val downloadUrl: String, val description: String?) {
        val extension: String get() = name.substringAfterLast('.', "").lowercase()
        val isArchive: Boolean get() = extension in ARCHIVE_EXTENSIONS
    }

    class ModDetails(
        val summary: ModSummary,
        val html: String,
        val version: String?,
        val downloads: Int,
        val screenshots: List<String>,
        val files: List<ModFile>,
        val updatedAt: Long?,
    )

    val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar")

    // --- Fetching (call off the main thread) ---

    fun list(categoryId: Int?, page: Int): ListPage {
        val filter = if (categoryId != null) "&_aFilters%5BGeneric_Category%5D=$categoryId" else ""
        val url = "$API/Mod/Index?_aFilters%5BGeneric_Game%5D=$GAME_ID$filter&_nPage=$page&_nPerpage=$PAGE_SIZE"
        return parseList(Http.getText(url, HOSTS))
    }

    /** Full-text search. GameBanana ignores the category filter here, so [category] is applied to
     * the results instead (WheelWizard does the same). */
    fun search(query: String, page: Int, category: Category?): ListPage {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$API/Util/Search/Results?_sSearchString=$q&_idGameRow=$GAME_ID&_sModelName=Mod&_nPage=$page"
        val result = parseList(Http.getText(url, HOSTS))
        val root = category?.rootName ?: return result
        return ListPage(result.mods.filter { it.category.equals(root, ignoreCase = true) }, result.total, result.complete)
    }

    fun details(id: Int): ModDetails = parseDetails(JSONObject(Http.getText("$API/Mod/$id/ProfilePage", HOSTS)))

    // --- Parsing (pure, unit-tested) ---

    fun parseList(json: String): ListPage {
        val root = JSONObject(json)
        val meta = root.optJSONObject("_aMetadata")
        val records = root.optJSONArray("_aRecords") ?: JSONArray()
        val mods = ArrayList<ModSummary>()
        for (i in 0 until records.length()) {
            val o = records.optJSONObject(i) ?: continue
            if (o.str("_sModelName")?.equals("Mod", ignoreCase = true) == false) continue
            mods += parseSummary(o)
        }
        val total = meta?.optInt("_nRecordCount", mods.size) ?: mods.size
        val complete = meta?.optBoolean("_bIsComplete", records.length() == 0) ?: true
        return ListPage(mods, total, complete || records.length() == 0)
    }

    private fun parseSummary(o: JSONObject): ModSummary {
        val images = images(o)
        return ModSummary(
            id = o.optInt("_idRow"),
            name = o.str("_sName") ?: "Untitled",
            author = o.optJSONObject("_aSubmitter")?.str("_sName") ?: "",
            category = (o.optJSONObject("_aRootCategory") ?: o.optJSONObject("_aCategory"))?.str("_sName") ?: "",
            thumbnailUrl = images.firstOrNull()?.let { it.url(it.file220 ?: it.file) },
            previewUrl = images.firstOrNull()?.let { it.url(it.file530 ?: it.file) },
            likes = o.optInt("_nLikeCount"),
            views = o.optInt("_nViewCount"),
            addedAt = o.optLong("_tsDateAdded") * 1000,
        )
    }

    fun parseDetails(o: JSONObject): ModDetails {
        val files = ArrayList<ModFile>()
        val array = o.optJSONArray("_aFiles") ?: JSONArray()
        for (i in 0 until array.length()) {
            val f = array.optJSONObject(i) ?: continue
            val url = f.str("_sDownloadUrl") ?: continue
            files += ModFile(f.optInt("_idRow"), f.str("_sFile") ?: "file", f.optLong("_nFilesize"), url, f.str("_sDescription"))
        }
        val updated = o.optLong("_tsDateUpdated").takeIf { it > 0 } ?: o.optLong("_tsDateModified").takeIf { it > 0 }
        return ModDetails(
            summary = parseSummary(o),
            html = o.str("_sText") ?: "",
            version = o.str("_sVersion"),
            downloads = o.optInt("_nDownloadCount"),
            screenshots = images(o).map { it.url(it.file530 ?: it.file) },
            files = files,
            updatedAt = updated?.times(1000),
        )
    }

    private class Image(val base: String, val file: String, val file220: String?, val file530: String?) {
        fun url(name: String) = "$base/$name"
    }

    private fun images(o: JSONObject): List<Image> {
        val array = o.optJSONObject("_aPreviewMedia")?.optJSONArray("_aImages") ?: return emptyList()
        val list = ArrayList<Image>()
        for (i in 0 until array.length()) {
            val image = array.optJSONObject(i) ?: continue
            val base = image.str("_sBaseUrl") ?: continue
            val file = image.str("_sFile") ?: continue
            if (!Http.isAllowed("$base/$file", HOSTS)) continue
            list += Image(base, file, image.str("_sFile220"), image.str("_sFile530"))
        }
        return list
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
