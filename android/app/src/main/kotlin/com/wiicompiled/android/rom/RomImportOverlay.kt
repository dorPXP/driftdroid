package com.wiicompiled.android.rom

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Shown full-screen over the (still blank) SDL surface when no extracted Mario Kart Wii DATA
 * folder exists yet at [dvdRoot] - runtime/src/hle/storage/dvd.cpp polls for that folder instead
 * of failing outright on Android specifically, for exactly this reason: this overlay and native
 * boot are racing, and native just waits for this to finish.
 *
 * This stage only picks and validates a disc image (region-checks a plain ISO/GCM by reading its
 * header directly; WBFS's header is behind its own block table, so that only gets a container-type
 * check for now). Extraction into [dvdRoot] itself is a follow-up - see the TODO in [onFilePicked].
 */
class RomImportOverlay private constructor(private val activity: Activity, private val dvdRoot: File) {

    private val container = FrameLayout(activity)
    private val statusText = TextView(activity)

    init {
        container.setBackgroundColor(Color.rgb(18, 18, 22))

        val column = LinearLayout(activity)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER
        val pad = dp(24)
        column.setPadding(pad, pad, pad, pad)

        val title = TextView(activity)
        title.text = "Please select the Mario Kart Wii ROM"
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        title.gravity = Gravity.CENTER

        val subtitle = TextView(activity)
        subtitle.text = "(ONLY PAL REGION SUPPORTED)"
        subtitle.setTextColor(Color.argb(200, 255, 210, 90))
        subtitle.textSize = 14f
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, dp(8), 0, dp(24))

        val button = Button(activity)
        button.text = "Select ROM file (.iso / .wbfs)"
        button.setOnClickListener { launchPicker() }

        statusText.setTextColor(Color.argb(200, 255, 255, 255))
        statusText.textSize = 13f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(16), 0, 0)

        column.addView(title)
        column.addView(subtitle)
        column.addView(button)
        column.addView(statusText)

        val columnParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        columnParams.gravity = Gravity.CENTER
        container.addView(column, columnParams)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        activity.startActivityForResult(intent, REQUEST_CODE_PICK_ROM)
    }

    /** Call from the activity's onActivityResult for [REQUEST_CODE_PICK_ROM]. */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "selected file"
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in ACCEPTED_EXTENSIONS) {
            showResult(false, "\"$name\" isn't a .iso/.gcm/.gcz/.wbfs file - pick your Mario Kart Wii disc image.")
            return
        }

        if (extension == "wbfs") {
            // WBFS wraps the disc header behind its own block table - reading the region code
            // needs the real WBFS parser (not built yet), so this only confirms the container
            // type for now.
            statusText.setTextColor(Color.WHITE)
            statusText.text =
                "\"$name\" looks like a WBFS image. Region check and extraction aren't " +
                    "implemented yet - nothing further happens with this pick."
            return
        }

        val gameId = readGameId(uri)
        when {
            gameId == null -> showResult(false, "Couldn't read \"$name\" - make sure it's a valid disc image.")
            gameId != "RMCP01" -> showResult(
                false,
                "\"$name\" is game ID $gameId, not RMCP01 (PAL Mario Kart Wii). Only the PAL release is supported.",
            )
            else -> {
                showResult(
                    true,
                    "\"$name\" is a valid PAL Mario Kart Wii disc (RMCP01). " +
                        "Extraction into the game isn't implemented yet.",
                )
                // TODO(extraction stage): decrypt/extract this disc image straight into
                // dvdRoot/sys and dvdRoot/files. dvd.cpp's WaitForAndroidDvdRoot poll picks up
                // the finished folder on its own - no further native wiring needed past that.
            }
        }
    }

    private fun showResult(success: Boolean, message: String) {
        statusText.setTextColor(if (success) Color.rgb(120, 255, 150) else Color.rgb(255, 110, 110))
        statusText.text = message
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }

    /** First 6 bytes of a GC/Wii disc image are the ASCII game ID (e.g. "RMCP01" for PAL MKWii). */
    private fun readGameId(uri: Uri): String? =
        try {
            activity.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(6)
                var read = 0
                while (read < header.size) {
                    val n = stream.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == header.size) String(header, Charsets.US_ASCII) else null
            }
        } catch (_: Exception) {
            null
        }

    companion object {
        const val REQUEST_CODE_PICK_ROM = 0x524F4D // "ROM"

        private val ACCEPTED_EXTENSIONS = setOf("iso", "gcm", "gcz", "wbfs")

        fun isRomAlreadyImported(dvdRoot: File): Boolean = File(dvdRoot, "sys/main.dol").isFile

        fun attach(activity: Activity, parent: ViewGroup, dvdRoot: File): RomImportOverlay {
            val overlay = RomImportOverlay(activity, dvdRoot)
            parent.addView(
                overlay.container,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            return overlay
        }
    }
}
