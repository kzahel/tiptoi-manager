package io.github.kzahel.tiptoimanager

import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class CatalogEntry(val id: Int, val version: String, val url: String, val filename: String, val size: Long) {
    val title: String get() = filename.removeSuffix(".gme").replace('_', ' ')
        .replace(Regex("(?<=[a-zäöü])(?=[A-ZÄÖÜ])"), " ")
    val cacheKey: String get() = sha256("$url|$version|$size".toByteArray()) + ".gme"
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

object Catalog {
    const val URL = "https://cdn.ravensburger.de/db/tiptoi2.csv"

    fun allowedUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "cdn.ravensburger.de" &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.fragment == null
    }.getOrDefault(false)

    // CSV fields can contain commas, quotes and newlines. Do not split by comma.
    fun rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                if (quoted && i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                else quoted = !quoted
            } else if (c == ',' && !quoted) {
                row.add(field.toString()); field.clear()
            } else if (c == '\n' && !quoted) {
                row.add(field.toString().trimEnd('\r')); field.clear()
                rows.add(row); row = mutableListOf()
            } else field.append(c)
            i++
        }
        require(!quoted) { t(R.string.error_the_catalog_contains_an_incomplete_quoted_field) }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trimEnd('\r')); rows.add(row)
        }
        return rows
    }

    fun parse(text: String): List<CatalogEntry> = rows(text).mapNotNull { row ->
        if (row.size != 5) return@mapNotNull null
        val id = row[0].toIntOrNull() ?: return@mapNotNull null
        val size = row[4].toLongOrNull() ?: return@mapNotNull null
        if ((id !in 1..999 && id != 14999) || size !in 28..2_000_000_000L) return@mapNotNull null
        if (!allowedUrl(row[2]) || !URI(row[2]).path.startsWith("/db/applications/") ||
            !URI(row[2]).path.endsWith(".gme", true)) return@mapNotNull null
        val filename = row[3].let { if (it.endsWith(".gme", true)) it else "$it.gme" }
        if (!safeFilename(filename)) return@mapNotNull null
        CatalogEntry(id, row[1], row[2], filename, size)
    }.distinctBy { Triple(it.filename, it.version, it.url) }.sortedBy { it.title.lowercase() }

    fun safeFilename(name: String): Boolean = name.isNotBlank() && !name.startsWith('.') &&
        name.length <= 200 && name.none { it == '/' || it == '\\' || it.code < 32 } && name.endsWith(".gme", true)
}

data class GmeResult(val id: Int, val size: Long, val sha256: String)

object Gme {
    fun productId(header: ByteArray): Int {
        require(header.size >= 24) { t(R.string.error_gme_header_is_incomplete) }
        require(word(header, 8) == 0x238bL) { t(R.string.error_not_a_recognized_gme_file) }
        val id = word(header, 20)
        require(id in 0..Int.MAX_VALUE.toLong()) { t(R.string.error_unsupported_product_id) }
        return id.toInt()
    }

    private fun word(bytes: ByteArray, offset: Int): Long = (0..3).sumOf {
        (bytes[offset + it].toLong() and 255) shl (it * 8)
    }

    fun inspect(input: InputStream, expectedSize: Long? = null, expectedId: Int? = null,
                progress: (Long) -> Unit = {}): GmeResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(24)
        val tail = ByteArray(4)
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        var sum = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            for (i in 0 until count) {
                val index = size + i
                if (index < 24) header[index.toInt()] = buffer[i]
                tail[(index % 4).toInt()] = buffer[i]
                sum = (sum + (buffer[i].toLong() and 255)) and 0xffffffffL
            }
            size += count
            digest.update(buffer, 0, count)
            require(expectedSize == null || size <= expectedSize) { t(R.string.error_file_is_larger_than_advertised) }
            progress(size)
        }
        require(size >= 28) { t(R.string.error_gme_file_is_incomplete) }
        val id = productId(header)
        require(expectedSize == null || expectedSize == size) { t(R.string.error_file_size_does_not_match_the_catalog) }
        require(expectedId == null || expectedId == id) { t(R.string.error_product_id_does_not_match_the_catalog) }
        val orderedTail = ByteArray(4) { tail[((size - 4 + it) % 4).toInt()] }
        val actual = (sum - orderedTail.sumOf { it.toLong() and 255 }) and 0xffffffffL
        require(word(orderedTail, 0) == actual) { t(R.string.error_gme_checksum_failed_file_may_be_incomplete_or_corrupt) }
        return GmeResult(id, size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}

object Downloads {
    fun fetch(url: String, destination: File, expectedSize: Long? = null, progress: (Long) -> Unit = {}) {
        var current = url
        repeat(6) {
            require(Catalog.allowedUrl(current)) { t(R.string.error_download_source_must_be_the_ravensburger_https_cdn) }
            val connection = URI(current).toURL().openConnection() as HttpsURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", PublisherHttp.AGENT)
            connection.instanceFollowRedirects = false
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error(t(R.string.error_redirect_has_no_destination))
                    current = URI(current).resolve(location).toString()
                } else {
                    check(code == 200) { t(R.string.error_the_download_server_returned_http_1_d, code) }
                    val limit = expectedSize ?: 5_000_000L
                    connection.inputStream.use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                check(total <= limit) { t(R.string.error_download_exceeded_the_expected_size) }
                                output.write(buffer, 0, n)
                                progress(total)
                            }
                            check(expectedSize == null || total == expectedSize) { t(R.string.error_download_ended_before_all_bytes_arrived) }
                        }
                    }
                    return
                }
            } finally { connection.disconnect() }
        }
        error(t(R.string.error_too_many_download_redirects))
    }
}
