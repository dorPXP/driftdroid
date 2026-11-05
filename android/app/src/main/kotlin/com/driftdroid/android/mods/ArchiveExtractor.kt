package com.driftdroid.android.mods

import android.os.ParcelFileDescriptor
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Unpacks a mod archive into a folder. Mario Kart Wii mods on GameBanana come as .zip (most),
 * .rar (nearly always RAR5) and .7z; zip goes through java.util.zip, and everything else - or a
 * zip java.util.zip can't read - through libarchive.
 *
 * Every entry is checked to land inside [extract]'s destination (no "../" escapes, no absolute
 * paths), and the unpacked total is capped so a hostile archive can't fill the phone.
 */
internal object ArchiveExtractor {
    enum class Format { ZIP, SEVEN_ZIP, RAR, UNKNOWN }

    private const val MAX_TOTAL_BYTES = 6L shl 30
    private const val MAX_ENTRIES = 50_000

    fun sniff(file: File): Format {
        val head = ByteArray(8)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 4) return Format.UNKNOWN
        fun at(vararg bytes: Int) = bytes.withIndex().all { (i, b) -> i < n && (head[i].toInt() and 0xFF) == b }
        return when {
            at(0x50, 0x4B, 0x03, 0x04) || at(0x50, 0x4B, 0x05, 0x06) -> Format.ZIP
            at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Format.SEVEN_ZIP
            at(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> Format.RAR
            else -> Format.UNKNOWN
        }
    }

    /** Unpacks [archive] into [destination] (created if needed). Throws with a readable message. */
    fun extract(archive: File, destination: File, onFile: (Int) -> Unit = {}) {
        destination.mkdirs()
        when (sniff(archive)) {
            Format.ZIP ->
                try {
                    extractZip(archive, destination, onFile)
                } catch (e: Exception) {
                    // Some zips (unusual compression, broken central directories) only libarchive reads.
                    destination.listFiles()?.forEach { it.deleteRecursively() }
                    extractWithLibarchive(archive, destination, onFile)
                }
            Format.SEVEN_ZIP, Format.RAR -> extractWithLibarchive(archive, destination, onFile)
            Format.UNKNOWN -> throw IOException("that isn't a .zip, .7z or .rar archive")
        }
        if (destination.walkTopDown().none { it.isFile }) throw IOException("the archive was empty")
    }

    private fun extractZip(archive: File, destination: File, onFile: (Int) -> Unit) {
        var total = 0L
        var count = 0
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = safeTarget(destination, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                if (++count > MAX_ENTRIES) throw IOException("the archive has too many files")
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL_BYTES) throw IOException("the archive unpacks to more than 6 GB")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                onFile(count)
            }
        }
    }

    private fun extractWithLibarchive(archive: File, destination: File, onFile: (Int) -> Unit) {
        val handle =
            try {
                Archive.readNew()
            } catch (e: Throwable) {
                throw IOException("this phone can't open .7z or .rar files (${e.message})")
            }
        var total = 0L
        var count = 0
        try {
            Archive.readSupportFilterAll(handle)
            Archive.readSupportFormatAll(handle)
            Archive.readOpenFileName(handle, archive.absolutePath.toByteArray(), 1L shl 16)
            while (true) {
                val entry =
                    try {
                        Archive.readNextHeader(handle)
                    } catch (e: ArchiveException) {
                        if (e.code == Archive.ERRNO_EOF) 0L else throw e
                    }
                if (entry == 0L) break
                val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.toString(Charsets.ISO_8859_1)
                val target = name?.let { safeTarget(destination, it) }
                val type = ArchiveEntry.filetype(entry)
                when {
                    target == null -> Archive.readDataSkip(handle)
                    type == ArchiveEntry.AE_IFDIR -> target.mkdirs()
                    type == ArchiveEntry.AE_IFREG -> {
                        if (ArchiveEntry.isEncrypted(entry)) throw IOException("the archive is password-protected")
                        if (++count > MAX_ENTRIES) throw IOException("the archive has too many files")
                        if (ArchiveEntry.sizeIsSet(entry)) {
                            total += ArchiveEntry.size(entry)
                            if (total > MAX_TOTAL_BYTES) throw IOException("the archive unpacks to more than 6 GB")
                        }
                        target.parentFile?.mkdirs()
                        ParcelFileDescriptor.open(
                            target,
                            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
                        ).use { Archive.readDataIntoFd(handle, it.fd) }
                        onFile(count)
                    }
                    // Links, devices and the like have no place in a mod.
                    else -> Archive.readDataSkip(handle)
                }
            }
        } catch (e: ArchiveException) {
            throw IOException(e.message?.takeIf { it.isNotBlank() } ?: "the archive couldn't be read", e)
        } finally {
            runCatching { Archive.readFree(handle) }
        }
    }

    /** Where [entryName] goes under [root], or null if it would land outside it. */
    fun safeTarget(root: File, entryName: String): File? {
        val clean = entryName.replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        val target = File(root, clean)
        val rootPath = root.canonicalPath + File.separator
        return if (target.canonicalPath.startsWith(rootPath)) target else null
    }
}
