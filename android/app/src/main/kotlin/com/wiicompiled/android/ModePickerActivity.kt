package com.wiicompiled.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The app's actual launcher (see AndroidManifest.xml). Chooses Original Mario Kart Wii vs. Retro
 * Rewind BEFORE any guest engine state exists, since neither the combined native library
 * (libGameCombined.so - both products, see runtime/cmake/build_combined_android_lib.py) nor a
 * running game has a supported way to reset and switch profiles live - MainActivity.EXTRA_PRODUCT
 * tells it which one nativeSetActiveProduct() should select.
 *
 * Retro Rewind's pack (tracks/textures, ~2GB - distinct from Code.pul, which only carries the
 * mod's patched game LOGIC and is already compiled into libGameCombined.so) is never bundled or
 * downloaded by this app, matching how the PC version works: WiiCompiled.Setup's
 * RetroRewindSource.cs only ever resolves a folder the user already has (normally obtained via the
 * separate Wheel Wizard tool) - it never packages or downloads it itself. This picker offers two
 * ways to hand over a copy the user already obtained: the .zip file exactly as downloaded (most
 * people will only ever have this - extracted first, matching how someone who downloaded Retro
 * Rewind normally has it), or an already-extracted folder for anyone who prefers that.
 */
class ModePickerActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var baseButton: Button
    private lateinit var retroButton: Button
    private lateinit var retroMenuButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private val mainHandler = Handler(Looper.getMainLooper())
    private var importThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshRetroButtonLabel()
    }

    override fun onDestroy() {
        importThread?.interrupt()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.rgb(18, 18, 22))
        val pad = dp(24)
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "WiiCompiled"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(28))

        baseButton = Button(this)
        baseButton.text = "Mario Kart Wii"
        baseButton.setOnClickListener { launchProduct(MainActivity.PRODUCT_BASE) }

        retroButton = Button(this)
        retroButton.setOnClickListener {
            if (isRetroRewindInstalled()) {
                launchProduct(MainActivity.PRODUCT_RETRO_REWIND)
            } else {
                showInstallRetroRewindDialog()
            }
        }

        val buttonParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
        buttonParams.topMargin = dp(12)

        retroMenuButton = Button(this)
        retroMenuButton.text = "⋮" // vertical ellipsis ("3 dots")
        retroMenuButton.setOnClickListener { showRetroRewindMenuDialog() }

        val retroRowParams =
            LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
        retroRowParams.topMargin = dp(12)
        val retroRow = LinearLayout(this)
        retroRow.orientation = LinearLayout.HORIZONTAL
        val retroButtonParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val retroMenuButtonParams =
            LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
        retroMenuButtonParams.marginStart = dp(8)
        retroRow.addView(retroButton, retroButtonParams)
        retroRow.addView(retroMenuButton, retroMenuButtonParams)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 1000
        progressBar.visibility = View.GONE

        statusText = TextView(this)
        statusText.setTextColor(Color.argb(200, 255, 255, 255))
        statusText.textSize = 13f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(16), 0, 0)

        root.addView(title)
        root.addView(baseButton, buttonParams)
        root.addView(retroRow, retroRowParams)
        root.addView(progressBar, buttonParams)
        root.addView(statusText)

        setContentView(root)
        refreshRetroButtonLabel()
    }

    private fun refreshRetroButtonLabel() {
        val installed = isRetroRewindInstalled()
        retroButton.text = if (installed) "Retro Rewind" else "Install Retro Rewind..."
        // Nothing to update or delete until a copy is actually installed.
        retroMenuButton.visibility = if (installed) View.VISIBLE else View.GONE
    }

    private fun showRetroRewindMenuDialog() {
        AlertDialog.Builder(this)
            .setTitle("Retro Rewind")
            .setItems(arrayOf("Update", "Delete")) { _, which ->
                when (which) {
                    0 -> showInstallRetroRewindDialog()
                    1 -> showDeleteRetroRewindDialog()
                }
            }
            .show()
    }

    private fun showDeleteRetroRewindDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Retro Rewind")
            .setMessage("This removes the installed Retro Rewind files from the app. You'll need to import them again to play.")
            .setPositiveButton("Delete") { _, _ ->
                retroRewindRoot().deleteRecursively()
                refreshRetroButtonLabel()
                statusText.setTextColor(Color.argb(200, 255, 255, 255))
                statusText.text = "Retro Rewind deleted."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isRetroRewindInstalled(): Boolean =
        File(retroRewindRoot(), "Binaries/Code.pul").isFile

    private fun retroRewindRoot(): File = File(filesDir, MainActivity.RETRO_REWIND_STAGING_SUBDIR)

    private fun launchProduct(product: String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_PRODUCT, product)
        startActivity(intent)
    }

    private fun showInstallRetroRewindDialog() {
        AlertDialog.Builder(this)
            .setTitle("Install Retro Rewind")
            .setMessage(
                "Retro Rewind is a separate, optional community mod - this app doesn't include or " +
                    "download it. Get your own copy first (search \"Retro Rewind Mario Kart Wii\" or " +
                    "use the Wheel Wizard tool), then come back here and select either the .zip file " +
                    "exactly as you downloaded it, or a folder if you've already extracted it.",
            )
            .setPositiveButton("Select .zip file") { _, _ -> launchRetroRewindZipPicker() }
            .setNeutralButton("Select folder") { _, _ -> launchRetroRewindFolderPicker() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchRetroRewindFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_CODE_PICK_RETRO_REWIND_FOLDER)
    }

    private fun launchRetroRewindZipPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        // Not restricted to "application/zip": many file managers/browsers hand a downloaded zip
        // over with a generic or missing MIME type - the extension check on the picked file (see
        // onActivityResult) is the real gate, same reasoning as RomImportOverlay's ISO/WBFS picker.
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_PICK_RETRO_REWIND_ZIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_PICK_RETRO_REWIND_FOLDER -> handleFolderPicked(data?.data)
            REQUEST_CODE_PICK_RETRO_REWIND_ZIP -> handleZipPicked(data?.data)
        }
    }

    private fun handleFolderPicked(treeUri: Uri?) {
        if (treeUri == null) return
        contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val picked = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val resolved = resolveRetroRewind6(picked)
        if (resolved == null) {
            statusText.setTextColor(Color.rgb(255, 110, 110))
            statusText.text =
                "That folder doesn't contain RetroRewind6/Binaries/Code.pul - pick the folder " +
                    "you extracted Retro Rewind into (or the RetroRewind6 folder itself)."
            return
        }
        startFolderImport(resolved)
    }

    private fun handleZipPicked(uri: Uri?) {
        if (uri == null) return
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "selected file"
        if (!name.substringAfterLast('.', "").equals("zip", ignoreCase = true)) {
            statusText.setTextColor(Color.rgb(255, 110, 110))
            statusText.text = "\"$name\" isn't a .zip file - pick the Retro Rewind archive you downloaded."
            return
        }
        startZipImport(uri, name)
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }

    /** Mirrors the PC installer's RetroRewindSource.ResolveRetroRewind6: accept either the
     * RetroRewind6 folder itself, its parent, or a one-level-deeper wrapper folder. */
    private fun resolveRetroRewind6(picked: DocumentFile): DocumentFile? {
        val candidates = mutableListOf(picked)
        picked.findFile("RetroRewind6")?.let { candidates.add(it) }
        for (child in picked.listFiles()) {
            if (child.isDirectory) {
                child.findFile("RetroRewind6")?.let { candidates.add(it) }
            }
        }
        return candidates.firstOrNull { candidate ->
            candidate.findFile("Binaries")?.findFile("Code.pul")?.isFile == true
        }
    }

    /** Same resolution rule as [resolveRetroRewind6], but over plain java.io.File - used after a
     * zip has already been extracted to a scratch directory. */
    private fun resolveRetroRewind6(picked: File): File? {
        val candidates = mutableListOf(picked, File(picked, "RetroRewind6"))
        picked.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                candidates.add(File(child, "RetroRewind6"))
            }
        }
        return candidates.firstOrNull { File(it, "Binaries/Code.pul").isFile }
    }

    private fun beginImportUi(statusMessage: String) {
        baseButton.isEnabled = false
        retroButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        // Indeterminate (spinning), not a 0-100% fill: neither the folder-copy nor the zip-extract
        // path knows the total file/byte count up front without a slow pre-scan, so a fixed
        // progress value just sat frozen at 0% the whole time - confirmed directly as "no progress
        // bar... which is weird" (it was there, just never moving, so it looked broken/absent).
        // The file-count text next to it already gives a live sense of movement.
        progressBar.isIndeterminate = true
        statusText.setTextColor(Color.WHITE)
        statusText.text = statusMessage
    }

    private fun finishImportUi(error: String?, filesCount: Int) {
        progressBar.isIndeterminate = false
        progressBar.visibility = View.GONE
        baseButton.isEnabled = true
        retroButton.isEnabled = true
        if (error == null) {
            statusText.setTextColor(Color.rgb(120, 255, 150))
            statusText.text = "Retro Rewind installed ($filesCount files)."
            refreshRetroButtonLabel()
        } else {
            // Leave no half-copied install behind - isRetroRewindInstalled() only checks for
            // Code.pul, and a partial copy that happened to include it would then look
            // "installed" while actually missing tracks/assets.
            retroRewindRoot().deleteRecursively()
            statusText.setTextColor(Color.rgb(255, 110, 110))
            statusText.text = "Install failed: $error"
        }
    }

    private fun startFolderImport(source: DocumentFile) {
        beginImportUi("Copying Retro Rewind files - this can take a while for ~2GB...")
        val destRoot = retroRewindRoot()
        val filesCopied = intArrayOf(0)
        val poller =
            object : Runnable {
                override fun run() {
                    statusText.text = "Copying Retro Rewind files... ${filesCopied[0]} files done"
                    mainHandler.postDelayed(this, 500)
                }
            }
        mainHandler.postDelayed(poller, 500)

        val thread =
            Thread {
                val error = copyTree(source, destRoot, filesCopied)
                mainHandler.post {
                    mainHandler.removeCallbacks(poller)
                    finishImportUi(error, filesCopied[0])
                }
            }
        importThread = thread
        thread.start()
    }

    private fun copyTree(source: DocumentFile, destDir: File, filesCopied: IntArray): String? {
        return try {
            destDir.mkdirs()
            for (child in source.listFiles()) {
                if (Thread.currentThread().isInterrupted) {
                    return "cancelled"
                }
                val name = child.name ?: continue
                if (child.isDirectory) {
                    val error = copyTree(child, File(destDir, name), filesCopied)
                    if (error != null) return error
                } else {
                    val uri = child.uri
                    contentResolver.openInputStream(uri)?.use { input ->
                        File(destDir, name).outputStream().use { output -> input.copyTo(output) }
                    } ?: return "couldn't open $name"
                    filesCopied[0]++
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }
    }

    private fun startZipImport(uri: Uri, name: String) {
        beginImportUi("Extracting \"$name\" - this can take a while for ~2GB...")
        val filesExtracted = intArrayOf(0)
        val poller =
            object : Runnable {
                override fun run() {
                    statusText.text = "Extracting \"$name\"... ${filesExtracted[0]} files done"
                    mainHandler.postDelayed(this, 500)
                }
            }
        mainHandler.postDelayed(poller, 500)

        val thread =
            Thread {
                val scratchDir = File(filesDir, "WiiCompiled/.retro_rewind_zip_tmp")
                val error = extractZipAndInstall(uri, scratchDir, filesExtracted)
                scratchDir.deleteRecursively()
                mainHandler.post {
                    mainHandler.removeCallbacks(poller)
                    finishImportUi(error, filesExtracted[0])
                }
            }
        importThread = thread
        thread.start()
    }

    private fun extractZipAndInstall(uri: Uri, scratchDir: File, filesExtracted: IntArray): String? {
        return try {
            scratchDir.deleteRecursively()
            scratchDir.mkdirs()
            val scratchCanonical = scratchDir.canonicalFile
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (Thread.currentThread().isInterrupted) {
                            return "cancelled"
                        }
                        val outFile = File(scratchDir, entry.name)
                        // Zip Slip protection: refuse any entry whose resolved path would land
                        // outside the scratch directory (e.g. via "../" segments in the entry
                        // name) - this is an archive the user downloaded from somewhere on the
                        // internet, not a trusted/signed source.
                        if (!outFile.canonicalPath.startsWith(scratchCanonical.path + File.separator) &&
                            outFile.canonicalPath != scratchCanonical.path
                        ) {
                            return "archive contains an unsafe file path"
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output -> zip.copyTo(output) }
                            filesExtracted[0]++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return "couldn't open the archive"

            val resolved = resolveRetroRewind6(scratchDir) ?: return "the archive doesn't contain RetroRewind6/Binaries/Code.pul"
            val destRoot = retroRewindRoot()
            destRoot.deleteRecursively()
            destRoot.parentFile?.mkdirs()
            if (!resolved.renameTo(destRoot)) {
                resolved.copyRecursively(destRoot, overwrite = true)
            }
            null
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }
    }

    companion object {
        private const val REQUEST_CODE_PICK_RETRO_REWIND_FOLDER = 0x52520000 // "RR"
        private const val REQUEST_CODE_PICK_RETRO_REWIND_ZIP = 0x52520001
    }
}
