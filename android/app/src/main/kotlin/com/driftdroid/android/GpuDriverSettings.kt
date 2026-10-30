package com.driftdroid.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Custom Vulkan drivers for Snapdragon phones (Turnip, or a newer Qualcomm driver), in the
 * AdrenoTools package format other Android emulators use: a .zip holding meta.json and the driver
 * .so. Many of the rendering faults reported on Adreno GPUs are driver bugs, and a different
 * driver is the only fix for those.
 *
 * Each package is unpacked into its own folder under [driversDir]. Selecting one writes
 * video.gpu_driver_dir / video.gpu_driver_lib into Config.toml, which the runtime reads at startup
 * (runtime/src/android_gpu_driver.cpp); selecting the system driver removes both keys. A package
 * that fails to load never stops the game: the runtime falls back to the system driver.
 */
object GpuDriverSettings {
    const val REQUEST_CODE_PICK_ZIP = 9822

    private const val SECTION = "video"
    private const val KEY_DIR = "gpu_driver_dir"
    private const val KEY_LIB = "gpu_driver_lib"
    private val hookLibraries =
        listOf("libmain_hook.so", "libhook_impl.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")

    private data class Driver(val dir: File, val name: String, val library: String, val details: String)

    private fun configFile(activity: Activity) = File(File(activity.filesDir, "WiiCompiled"), "Config.toml")

    private fun driversDir(activity: Activity) = File(File(activity.filesDir, "WiiCompiled"), "gpu_drivers")

    private fun installed(activity: Activity): List<Driver> =
        driversDir(activity)
            .listFiles { file -> file.isDirectory && File(file, "meta.json").isFile }
            .orEmpty()
            .mapNotNull { dir -> readDriver(dir) }
            .sortedBy { it.name.lowercase() }

    private fun readDriver(dir: File): Driver? =
        runCatching {
            val meta = JSONObject(File(dir, "meta.json").readText())
            val library = meta.getString("libraryName")
            if (!File(dir, library).isFile) return null
            val details =
                listOf(meta.optString("driverVersion"), meta.optString("description"))
                    .filter { it.isNotBlank() }
                    .joinToString(" - ")
            Driver(dir, meta.optString("name").ifBlank { dir.name }, library, details)
        }.getOrNull()

    private fun readKey(activity: Activity, key: String): String? =
        runCatching {
            configFile(activity)
                .takeIf { it.isFile }
                ?.readLines()
                ?.firstOrNull { it.trimStart().startsWith(key) }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"')
        }.getOrNull()

    /** Sets [key] under [video], or removes it when [value] is null, leaving other lines alone. */
    private fun writeKey(lines: MutableList<String>, key: String, value: String?) {
        val existing = lines.indexOfFirst { it.trimStart().startsWith(key) }
        if (value == null) {
            if (existing >= 0) lines.removeAt(existing)
            return
        }
        val entry = "$key = \"$value\""
        if (existing >= 0) {
            lines[existing] = entry
            return
        }
        val section = lines.indexOfFirst { it.trim() == "[$SECTION]" }
        if (section >= 0) {
            lines.add(section + 1, entry)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[$SECTION]")
            lines.add(entry)
        }
    }

    private fun select(activity: Activity, driver: Driver?): Boolean =
        runCatching {
            if (driver != null) copyHookLibraries(activity)
            val file = configFile(activity)
            file.parentFile?.mkdirs()
            val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
            writeKey(lines, KEY_DIR, driver?.dir?.absolutePath?.plus("/"))
            writeKey(lines, KEY_LIB, driver?.library)
            file.writeText(lines.joinToString("\n") + "\n")
        }.isSuccess

    /**
     * The runtime needs the driver-loading hook libraries as real files. They ship inside the APK,
     * which Android leaves packed, so copy them out next to the driver folders.
     */
    private fun copyHookLibraries(activity: Activity) {
        val hooks = File(driversDir(activity), "hooks")
        hooks.mkdirs()
        ZipFile(activity.applicationInfo.sourceDir).use { apk ->
            for (name in hookLibraries) {
                val entry = apk.getEntry("lib/arm64-v8a/$name") ?: error("$name is missing from the app")
                apk.getInputStream(entry).use { input ->
                    File(hooks, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** Pick a driver (saved on tap) or install one from a .zip. */
    fun show(activity: Activity) {
        val drivers = installed(activity)
        val currentDir = readKey(activity, KEY_DIR)?.trimEnd('/')
        val current = drivers.indexOfFirst { it.dir.absolutePath == currentDir } + 1
        val sheet = DriftSheet(activity, "GPU driver", T("Snapdragon phones only. Used with Vulkan"))
        val rows = ArrayList<android.widget.LinearLayout>()
        fun pick(index: Int) {
            if (select(activity, drivers.getOrNull(index - 1))) {
                rows.forEachIndexed { i, row -> sheet.setPicked(row, i == index) }
            } else {
                Toast.makeText(activity, T("Could not save the driver choice."), Toast.LENGTH_LONG).show()
            }
        }
        rows += sheet.radio("System driver", "The driver that came with the phone", current == 0) { pick(0) }
        drivers.forEachIndexed { i, driver ->
            rows += sheet.radio(driver.name, driver.details.ifBlank { null }, current == i + 1) { pick(i + 1) }
        }
        sheet.note("A new choice is used the next time the game starts.")
        sheet.button("Install .zip") {
            sheet.dismiss()
            pickZip(activity)
        }
        sheet.button("Done", DriftUi.Style.PRIMARY) { sheet.dismiss() }
        sheet.show()
    }

    private fun pickZip(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        // Driver packages are often named *.adpkg.zip and get reported with assorted MIME types.
        intent.type = "*/*"
        activity.startActivityForResult(intent, REQUEST_CODE_PICK_ZIP)
    }

    /** Call from the activity's onActivityResult for [REQUEST_CODE_PICK_ZIP]. */
    fun onZipPicked(activity: Activity, uri: Uri?) {
        if (uri == null) return
        Thread {
            val error = runCatching { install(activity, uri) }.exceptionOrNull()?.let { it.message ?: it.toString() }
            activity.runOnUiThread {
                if (error != null) {
                    DriftSheet.message(activity, "Driver not installed", error)
                } else {
                    show(activity)
                }
            }
        }.start()
    }

    private fun install(activity: Activity, uri: Uri) {
        val staging = File(driversDir(activity), ".staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val input = activity.contentResolver.openInputStream(uri) ?: error(T("Could not open that file."))
            input.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var total = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // Packages are flat; keeping only the file name also rules out path tricks.
                        val name = File(entry.name).name
                        if (entry.isDirectory || name.isEmpty() || name.startsWith(".")) continue
                        File(staging, name).outputStream().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > 512L * 1024 * 1024) error(T("That file is too large to be a driver package."))
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val metaFile = File(staging, "meta.json")
            if (!metaFile.isFile) error(T("That is not a driver package (no meta.json inside the zip)."))
            val meta = JSONObject(metaFile.readText())
            val library = meta.optString("libraryName")
            if (library.isBlank() || !File(staging, library).isFile) {
                error(T("The driver package is incomplete (its driver file is missing)."))
            }
            val minApi = meta.optInt("minApi", 0)
            if (minApi > Build.VERSION.SDK_INT) {
                error(T("This driver needs a newer Android version than this device has."))
            }
            val name = meta.optString("name").ifBlank { "driver" }
            val folder = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifBlank { "driver" }
            val target = File(driversDir(activity), folder)
            target.deleteRecursively()
            if (!staging.renameTo(target)) error(T("Could not store the driver."))
        } finally {
            staging.deleteRecursively()
        }
    }
}
