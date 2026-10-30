package com.driftdroid.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.driftdroid.android.ui.DriftSheet
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

/**
 * Import and export of the game's save file, rksys.dat (GitHub issue #14: bring a profile over
 * from a Wii NAND backup or Dolphin, and take it back out again).
 *
 * Mario Kart Wii keeps its save in the emulated NAND. Retro Rewind is launched with a Riivolution
 * <savegame> redirect, so it has its own copy under riivolution/save/RetroWFC/RMCP (see
 * nand_fs.cpp's ResolveRiivolutionSaveHostPath); the two are separate saves.
 */
internal object SaveData {
    const val REQUEST_CODE_EXPORT = 0x53560000 // "SV"
    const val REQUEST_CODE_IMPORT = 0x53560001

    /** rksys.dat is always this size: 0x2BC000 bytes. */
    private const val SAVE_BYTES = 0x2BC000
    /** "RKSD" header, then a CRC-32 of the first 0x27FFC bytes stored big-endian at 0x27FFC. */
    private const val CHECKSUM_OFFSET = 0x27FFC

    private enum class Game(val label: String, val relativePath: String) {
        MarioKartWii("Mario Kart Wii", "WiiCompiled/NAND/title/00010004/524d4350/data/rksys.dat"),
        RetroRewind("Retro Rewind", "WiiCompiled/riivolution/save/RetroWFC/RMCP/rksys.dat"),
    }

    // Which game the picker that is open right now belongs to.
    private var pendingGame: Game = Game.MarioKartWii

    private fun saveFile(activity: Activity, game: Game): File = File(activity.filesDir, game.relativePath)

    fun showDialog(activity: Activity) {
        val sheet = DriftSheet(activity, "Save data", T("Licenses, unlocks and ghosts"))
        sheet.note(
            "Bring in a save (rksys.dat) from a Wii, Dolphin or another device, or copy yours out. " +
                "Mario Kart Wii and Retro Rewind keep separate saves. Importing keeps a backup of the " +
                "current save (rksys.dat.bak).",
        )
        for (game in Game.values()) {
            sheet.section(game.label)
            sheet.action("Export", "Save a copy where you choose") {
                sheet.dismiss()
                launchExport(activity, game)
            }
            sheet.action("Import", "Replace this save with a file") {
                sheet.dismiss()
                launchImport(activity, game)
            }
        }
        sheet.show()
    }

    private fun launchExport(activity: Activity, game: Game) {
        if (!saveFile(activity, game).isFile) {
            showResult(activity, T("No %s save yet - play it once first.").format(game.label))
            return
        }
        pendingGame = game
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        val prefix = if (game == Game.RetroRewind) "RetroRewind" else "MarioKartWii"
        intent.putExtra(Intent.EXTRA_TITLE, "$prefix-rksys-$stamp.dat")
        activity.startActivityForResult(intent, REQUEST_CODE_EXPORT)
    }

    private fun launchImport(activity: Activity, game: Game) {
        pendingGame = game
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        activity.startActivityForResult(intent, REQUEST_CODE_IMPORT)
    }

    fun onExportDestinationPicked(activity: Activity, destination: Uri?) {
        if (destination == null) return
        val game = pendingGame
        try {
            activity.contentResolver.openOutputStream(destination)?.use { output ->
                saveFile(activity, game).inputStream().use { it.copyTo(output) }
            } ?: throw IllegalStateException("couldn't open the chosen destination")
            showResult(activity, "${game.label} save exported.")
        } catch (e: Exception) {
            showResult(activity, T("Export failed: %s").format(e.message ?: "unknown error"))
        }
    }

    fun onImportSourcePicked(activity: Activity, source: Uri?) {
        if (source == null) return
        val game = pendingGame
        val picked =
            try {
                activity.contentResolver.openInputStream(source)?.use { it.readBytes() }
                    ?: throw IllegalStateException("couldn't open the picked file")
            } catch (e: Exception) {
                showResult(activity, T("Import failed: %s").format(e.message ?: "unknown error"))
                return
            }
        val problem = validate(picked)
        if (problem != null) {
            showResult(activity, T("Import failed: %s").format(problem))
            return
        }
        DriftSheet.confirm(
            activity,
            T("Replace the %s save?").format(game.label),
            T("Your %s save (licenses, unlocks, ghosts) is replaced with the picked file. The current save is kept as rksys.dat.bak.").format(game.label),
            "Replace",
            danger = true,
        ) { writeImport(activity, game, picked) }
    }

    private fun writeImport(activity: Activity, game: Game, bytes: ByteArray) {
        try {
            val dest = saveFile(activity, game)
            dest.parentFile?.mkdirs()
            if (dest.isFile) dest.copyTo(File(dest.parentFile, "rksys.dat.bak"), overwrite = true)
            val temporary = File(dest.parentFile, "rksys.dat.importing")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(dest)) {
                temporary.delete()
                throw IllegalStateException("couldn't replace the save file")
            }
            showResult(activity, "${game.label} save imported.")
        } catch (e: Exception) {
            showResult(activity, T("Import failed: %s").format(e.message ?: "unknown error"))
        }
    }

    /** Null when [bytes] is a real Mario Kart Wii save, otherwise what is wrong with it. */
    internal fun validate(bytes: ByteArray): String? {
        if (bytes.size != SAVE_BYTES) {
            return "that isn't a Mario Kart Wii save (rksys.dat is $SAVE_BYTES bytes, this file is ${bytes.size})"
        }
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RKSD") {
            return "that isn't a Mario Kart Wii save (no RKSD header)"
        }
        val crc = CRC32()
        crc.update(bytes, 0, CHECKSUM_OFFSET)
        val stored = ByteBuffer.wrap(bytes, CHECKSUM_OFFSET, 4).int.toLong() and 0xFFFFFFFFL
        if (crc.value != stored) {
            return "the save is damaged (its checksum doesn't match)"
        }
        return null
    }

    private fun showResult(activity: Activity, text: String) {
        DriftSheet.message(activity, "Save data", text)
    }
}
