package io.github.kzahel.tiptoimanager

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val catalog = """{"products":[
        {"id":"10001","name":"Horse","description":"<script>bad()</script><p>A story.</p>","images":[
            {"dimension":"70","url":"https://cdn.ravensburger.de/images/70/horse.jpg"},
            {"dimension":"210","url":"https://ravensburger.cloud/images/210/horse.jpg"},
            {"dimension":"360","url":"https://ravensburger.cloud/images/360/horse.jpg"}],
            "gameFiles":[{"id":"53","fileName":"Horse.gme"}]},
        {"id":"10002","name":"Dog","images":[{"url":"https://evil.invalid/images/dog.jpg"}],
            "gameFiles":[{"id":"53","fileName":"Dog.gme"}]},
        {"id":"10003","name":"Horse","gameFiles":[{"id":"53","fileName":"Horse.gme"}]}
    ]}"""

    @Test fun joinsByIdAndFilenameWithoutLosingEditions() {
        val books = BookMetadataParser.parseCatalog(catalog)
        assertEquals(2, BookMetadataParser.matching(books, "horse.gme", 53).size)
        assertEquals(listOf("10002"), BookMetadataParser.matching(books, "Dog.gme", 53).map { it.article })
        assertTrue(BookMetadataParser.matching(books, "Horse.gme", 68).isEmpty())
        assertEquals("A story.", books.first().description)
        assertTrue(books.first().thumbUrl!!.contains("/210/"))
        assertTrue(books.first().coverUrl!!.contains("/360/"))
        assertNull(books[1].coverUrl)
    }

    @Test fun extractsOnlyPublisherConfirmedIsbnForMatchingArticle() {
        val html = """<script type="application/ld+json">{"@type":"Product","mpn":"49230","image":"https://ravensburger.cloud/images/book.webp"}</script>
            <div><div class="subtitle-1">ISBN:</div><div>978-3-473-49230-5</div></div>
            <div><div>Anzahl der Seiten:</div><div>16</div></div>"""
        val page = BookMetadataParser.parsePage(html, "49230", 123)
        assertEquals("978-3-473-49230-5", page.isbn)
        assertEquals(16, page.pages)
        assertEquals(123L, page.fetchedAt)
        assertThrows(IllegalArgumentException::class.java) { BookMetadataParser.parsePage(html, "32918") }
        assertNull(BookMetadataParser.parsePage(html.replace("49230-5", "49230-6"), "49230").isbn)
    }

    @Test fun cachesMetadataAndPublisherDetailsWithoutAnyNetwork() {
        val directory = temporary.newFolder()
        File(directory, "catalog.json").writeText(catalog)
        File(directory, "page-10001.json").writeText("""{"isbn":"978-3-473-49230-5","pages":16,"fetchedAt":123}""")
        val store = BookMetadataStore(directory, "", "")
        store.loadCached()
        assertEquals(3, store.books.size)
        assertEquals("978-3-473-49230-5", store.page("10001")?.isbn)
        assertEquals(2, store.forFile("Horse.gme", 53).size)
    }

    @Test fun restrictsImageAndPageAddresses() {
        assertTrue(PublisherUrls.image("https://cdn.ravensburger.de/images/book.jpg"))
        assertTrue(PublisherUrls.image("https://ravensburger.cloud/images/book.webp"))
        assertFalse(PublisherUrls.image("http://cdn.ravensburger.de/images/book.jpg"))
        assertFalse(PublisherUrls.image("https://cdn.ravensburger.de.evil.invalid/images/book.jpg"))
        assertFalse(PublisherUrls.image("https://user@ravensburger.cloud/images/book.jpg"))
        assertFalse(PublisherUrls.image("https://ravensburger.cloud/oauth/token"))
        assertFalse(PublisherUrls.page("https://evil.invalid/de-DE/pdp/49230"))
        assertTrue(PublisherUrls.page("https://www.ravensburger.de/de-DE/pdp/49230"))
        assertTrue(BookMetadataParser.validIsbn("9783473492305"))
        assertFalse(BookMetadataParser.validIsbn("9783473492306"))
    }
}
