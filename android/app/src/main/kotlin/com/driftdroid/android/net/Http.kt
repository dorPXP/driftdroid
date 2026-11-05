package com.driftdroid.android.net

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The launcher's one way onto the internet, for the online pages (Retro WFC rooms and leaderboard,
 * GameBanana mods, Mii pictures).
 *
 * Every request names the hosts it may talk to, and redirects are followed by hand so each hop is
 * checked against that list too (HttpURLConnection would follow one anywhere, and silently drops
 * HTTPS-to-HTTPS cross-host redirects). HTTPS only, and a response can never deliver more than the
 * caller's limit, whatever its headers claim. Same rules as RetroRewindCatalog.openStream.
 */
internal object Http {
    @Volatile var userAgent: String = "DriftDroid (Android)"

    class HttpException(val code: Int) : IOException("the server answered HTTP $code")

    class CancelledException : IOException("cancelled")

    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** HTTPS to one of [hosts]; an entry like "*.example.com" allows any subdomain of it (GameBanana
     * hands downloads to numbered mirrors such as filecache53.gamebanana.com). */
    fun isAllowed(url: String, hosts: Set<String>): Boolean =
        try {
            val parsed = URL(url)
            val host = parsed.host.lowercase()
            parsed.protocol == "https" &&
                (host in hosts || hosts.any { it.startsWith("*.") && host.endsWith(it.substring(1)) })
        } catch (_: Exception) {
            false
        }

    /** An open response body. Close it (use {}) to release the connection. */
    class Response internal constructor(
        val stream: InputStream,
        val contentLength: Long,
        private val connection: HttpURLConnection,
    ) : java.io.Closeable {
        override fun close() {
            try {
                stream.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    fun open(url: String, hosts: Set<String>, maxBytes: Long, accept: String? = null): Response {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!isAllowed(current, hosts)) throw IOException("refusing to connect to ${hostOf(current)}")
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                if (accept != null) setRequestProperty("Accept", accept)
            }
            val code =
                try {
                    connection.responseCode
                } catch (e: IOException) {
                    connection.disconnect()
                    throw e
                }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current = URL(URL(current), location ?: throw IOException("redirect with no Location")).toString()
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw HttpException(code)
            }
            val declared = connection.contentLengthLong
            if (declared > maxBytes) {
                connection.disconnect()
                throw IOException("the response is larger than expected")
            }
            return Response(Bounded(connection.inputStream, maxBytes), declared, connection)
        }
        throw IOException("too many redirects")
    }

    fun getBytes(url: String, hosts: Set<String>, maxBytes: Long = 8L shl 20, accept: String? = null): ByteArray =
        open(url, hosts, maxBytes, accept).use { it.stream.readBytes() }

    fun getText(url: String, hosts: Set<String>, maxBytes: Long = 8L shl 20): String =
        getBytes(url, hosts, maxBytes, "application/json").toString(Charsets.UTF_8)

    /**
     * Streams [url] into [destination] (through a .part file, so a broken download never looks
     * finished). [onProgress] gets (bytes so far, total or -1) a few times a second at most;
     * [cancelled] is checked between reads.
     */
    fun download(
        url: String,
        hosts: Set<String>,
        destination: File,
        maxBytes: Long,
        cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        destination.parentFile?.mkdirs()
        val part = File(destination.path + ".part")
        try {
            open(url, hosts, maxBytes).use { response ->
                part.outputStream().buffered(1 shl 16).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw CancelledException()
                        val n = response.stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) {
                            lastReport = now
                            onProgress(done, response.contentLength)
                        }
                    }
                    onProgress(done, response.contentLength)
                    if (response.contentLength > 0 && done != response.contentLength) {
                        throw IOException("the download stopped early")
                    }
                }
            }
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) throw IOException("couldn't store the download")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /** A short, human reason for a failed request. */
    fun describe(error: Throwable): String =
        when (error) {
            is HttpException -> error.message ?: "server error"
            is java.net.UnknownHostException -> "no internet connection"
            is java.net.SocketTimeoutException -> "the server took too long to answer"
            is javax.net.ssl.SSLException -> "a secure connection couldn't be made"
            is CancelledException -> "cancelled"
            else -> error.message ?: "network error"
        }

    private class Bounded(private val delegate: InputStream, private val limit: Long) : InputStream() {
        private var read = 0L

        private fun count(n: Int): Int {
            if (n > 0) {
                read += n
                if (read > limit) throw IOException("the response is larger than expected")
            }
            return n
        }

        override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = count(delegate.read(b, off, len))

        override fun close() = delegate.close()
    }
}
