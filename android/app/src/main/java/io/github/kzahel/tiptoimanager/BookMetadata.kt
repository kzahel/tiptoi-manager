package io.github.kzahel.tiptoimanager

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
private fun plain(value: String?): String = value?.let { Jsoup.parse(it).text().trim() }.orEmpty()

data class AudioReference(val id: Int, val filename: String)
data class BookInfo(val article: String, val title: String, val series: String?, val description: String,
    val shortDescription: String, val ageFrom: Int?, val ageTo: Int?, val thumbUrl: String?, val coverUrl: String?,
    val audioFiles: List<AudioReference>) {
    val publisherUrl: String get() = "https://www.ravensburger.de/de-DE/pdp/$article"
}
data class PublisherDetails(val isbn: String?, val pages: Int?, val coverUrl: String?, val fetchedAt: Long)

object PublisherUrls {
    const val TOKEN = "https://oauth.ravensburger.com/oauth/token"
    const val CATALOG = "https://ttapiv2.ravensburger.com/api/v2/catalog/de_DE"
    fun image(value: String): Boolean = valid(value) { uri ->
        uri.host in setOf("cdn.ravensburger.de", "ravensburger.cloud") && uri.path.startsWith("/images/")
    }
    fun page(value: String): Boolean = valid(value) { uri ->
        uri.host == "www.ravensburger.de" &&
            (uri.path.startsWith("/de-DE/pdp/") || Regex("/[0-9]+/product\\.html").matches(uri.path))
    }
    private fun valid(value: String, predicate: (URI) -> Boolean): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) && predicate(uri)
    }.getOrDefault(false)
}

object BookMetadataParser {
    fun parseCatalog(json: String): List<BookInfo> {
        val products = JSONObject(json).getJSONArray("products")
        require(products.length() in 1..10_000) { t(R.string.error_publisher_catalog_has_an_unexpected_size) }
        return products.objects().mapNotNull { product ->
            val article = product.text("id")?.takeIf { it.matches(Regex("[0-9]{1,8}")) } ?: return@mapNotNull null
            val title = plain(product.text("name")).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val files = product.optJSONArray("gameFiles")?.objects().orEmpty().mapNotNull { file ->
                val id = file.text("id")?.toIntOrNull() ?: return@mapNotNull null
                val filename = file.text("fileName")?.takeIf(Catalog::safeFilename) ?: return@mapNotNull null
                AudioReference(id, filename)
            }.distinct()
            if (files.isEmpty()) return@mapNotNull null
            val images = product.optJSONArray("images")?.objects().orEmpty().mapNotNull { item ->
                item.text("url")?.takeIf(PublisherUrls::image)?.let { Pair(item.optString("dimension").toIntOrNull() ?: 0, it) }
            }.sortedBy { it.first }
            val thumb = images.firstOrNull { it.first >= 210 }?.second ?: images.lastOrNull()?.second
            BookInfo(article, title, plain(product.text("series")).ifBlank { null }, plain(product.text("description")),
                plain(product.text("shortDescription")), product.optInt("ageFrom").takeIf { it > 0 },
                product.optInt("ageTo").takeIf { it > 0 }, thumb, images.lastOrNull()?.second, files)
        }
    }

    fun matching(books: List<BookInfo>, filename: String, id: Int): List<BookInfo> = books.filter { book ->
        book.audioFiles.any { it.id == id && it.filename.equals(filename, true) }
    }

    fun parsePage(html: String, article: String, now: Long = System.currentTimeMillis()): PublisherDetails {
        val document = Jsoup.parse(html)
        val product = document.select("script[type=application/ld+json]").mapNotNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
        }.firstOrNull { it.optString("@type") == "Product" && it.optString("mpn") == article }
        require(product != null) { t(R.string.error_the_publisher_page_did_not_match_this_edition) }
        fun field(label: String): String? = document.getElementsByTag("div")
            .firstOrNull { it.ownText().trim().removeSuffix(":").equals(label, true) }?.nextElementSibling()?.text()
        val isbn = (product.text("isbn") ?: field("ISBN"))?.takeIf(::validIsbn)
        val pages = field("Anzahl der Seiten")?.toIntOrNull()?.takeIf { it in 1..10_000 }
        val cover = product.text("image")?.takeIf(PublisherUrls::image)
        return PublisherDetails(isbn, pages, cover, now)
    }

    fun validIsbn(value: String): Boolean {
        val digits = value.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        return when {
            digits.matches(Regex("97[89][0-9]{10}")) -> digits.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum() % 10 == 0
            digits.matches(Regex("[0-9]{9}[0-9X]")) -> digits.mapIndexed { i, c -> (if (c == 'X') 10 else c.digitToInt()) * (10 - i) }.sum() % 11 == 0
            else -> false
        }
    }
}

/** Requests are direct to the publisher. Authentication never follows a redirect. */
object PublisherHttp {
    const val AGENT = "PenLibrary/0.1 (+https://github.com/kzahel/tiptoi-manager)"
    fun get(url: String, limit: Int, allowed: (String) -> Boolean, authorization: String? = null,
            form: String? = null): ByteArray {
        var current = url
        repeat(5) {
            require(allowed(current)) { t(R.string.error_unexpected_publisher_address) }
            val connection = URI(current).toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", AGENT)
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            try {
                if (form != null) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { output -> output.write(form.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    check(authorization == null && form == null) { t(R.string.error_publisher_authentication_redirected_unexpectedly) }
                    val next = connection.getHeaderField("Location") ?: error(t(R.string.error_missing_redirect_address))
                    current = URI(current).resolve(next).toString()
                } else {
                    check(code == 200) { t(R.string.error_publisher_data_unavailable_http_1_d, code) }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            check(output.size() + n <= limit) { t(R.string.error_publisher_response_was_too_large) }
                            output.write(buffer, 0, n)
                        }
                    }
                    return output.toByteArray()
                }
            } finally { connection.disconnect() }
        }
        error(t(R.string.error_too_many_publisher_redirects))
    }
}

class BookMetadataStore(private val directory: File, private val clientId: String, private val clientSecret: String) {
    @Volatile var books: List<BookInfo> = emptyList()
        private set
    private val pages = ConcurrentHashMap<String, PublisherDetails>()
    val attemptedPages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val catalogFile get() = File(directory, "catalog.json")

    fun loadCached() {
        directory.mkdirs()
        if (catalogFile.exists()) runCatching { books = BookMetadataParser.parseCatalog(catalogFile.readText()) }
        directory.listFiles()?.filter { it.name.startsWith("page-") && it.extension == "json" }?.forEach { file ->
            runCatching {
                val value = JSONObject(file.readText())
                pages[file.name.removePrefix("page-").removeSuffix(".json")] = PublisherDetails(value.text("isbn"),
                    value.optInt("pages").takeIf { it > 0 }, value.text("cover")?.takeIf(PublisherUrls::image), value.optLong("fetchedAt"))
            }
        }
    }

    fun refresh() {
        check(clientId.isNotBlank() && clientSecret.isNotBlank()) { t(R.string.error_this_build_has_no_publisher_catalog_configuration) }
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
        val response = PublisherHttp.get(PublisherUrls.TOKEN, 64_000, { it == PublisherUrls.TOKEN },
            "Basic $basic", "grant_type=client_credentials")
        val token = JSONObject(response.toString(Charsets.UTF_8)).getString("access_token")
        check(token.isNotBlank()) { t(R.string.error_publisher_catalog_authorization_was_unavailable) }
        val data = PublisherHttp.get(PublisherUrls.CATALOG, 20_000_000, { it == PublisherUrls.CATALOG }, "Bearer $token")
        val parsed = BookMetadataParser.parseCatalog(data.toString(Charsets.UTF_8))
        check(parsed.isNotEmpty()) { t(R.string.error_publisher_catalog_contained_no_matching_content) }
        atomicWrite(catalogFile, data)
        books = parsed; attemptedPages.clear()
    }

    fun forFile(filename: String, id: Int?): List<BookInfo> = if (id == null) emptyList() else BookMetadataParser.matching(books, filename, id)
    fun forEntry(entry: CatalogEntry): List<BookInfo> = forFile(entry.filename, entry.id)
    fun page(article: String): PublisherDetails? = pages[article]
    fun pageFresh(article: String): Boolean = pages[article]?.let { System.currentTimeMillis() - it.fetchedAt < 7 * 86_400_000L } == true

    fun fetchPage(book: BookInfo): PublisherDetails {
        val html = PublisherHttp.get(book.publisherUrl, 3_000_000, PublisherUrls::page).toString(Charsets.UTF_8)
        val details = BookMetadataParser.parsePage(html, book.article)
        val json = JSONObject().put("isbn", details.isbn).put("pages", details.pages)
            .put("cover", details.coverUrl).put("fetchedAt", details.fetchedAt)
        atomicWrite(File(directory, "page-${book.article}.json"), json.toString().toByteArray(Charsets.UTF_8))
        pages[book.article] = details
        return details
    }
}

internal fun atomicWrite(destination: File, bytes: ByteArray) {
    destination.parentFile?.mkdirs()
    val temporary = File(destination.parentFile, destination.name + ".part")
    try {
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        check(temporary.renameTo(destination)) { t(R.string.error_could_not_save_downloaded_details) }
    } finally { temporary.delete() }
}
