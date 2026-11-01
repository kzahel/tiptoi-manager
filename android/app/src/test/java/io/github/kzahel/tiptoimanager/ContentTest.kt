package io.github.kzahel.tiptoimanager

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ContentTest {
    private fun gme(id: Int): ByteArray {
        val file = ByteArray(65) { it.toByte() }
        val words = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        words.putInt(8, 0x238b); words.putInt(20, id)
        words.putInt(file.size - 4, file.take(file.size - 4).sumOf { it.toInt() and 255 })
        return file
    }

    @Test fun validatesChecksumIncludingNonAlignedTrailer() {
        val file = gme(77)
        assertEquals(77, Gme.inspect(file.inputStream(), file.size.toLong(), 77).id)
    }
    @Test fun rejectsCorruptionWrongIdAndTruncation() {
        val file = gme(77)
        assertThrows(IllegalArgumentException::class.java) { Gme.inspect(file.inputStream(), file.size.toLong(), 248) }
        assertThrows(IllegalArgumentException::class.java) { Gme.inspect(file.dropLast(1).toByteArray().inputStream(), file.size.toLong()) }
        file[40] = 0
        assertThrows(IllegalArgumentException::class.java) { Gme.inspect(file.inputStream()) }
    }
    @Test fun acceptsShortReadsAcrossHeaderAndChecksumBoundaries() {
        val file = gme(248)
        val input = object : java.io.ByteArrayInputStream(file) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 7))
        }
        assertEquals(248, Gme.inspect(input, file.size.toLong(), 248).id)
        file[8] = 0
        assertThrows(IllegalArgumentException::class.java) { Gme.productId(file) }
    }
    @Test fun csvPreservesCompanionFilesAndRejectsFirmwareOrUnsafeDestinations() {
        val csv = """
            Items._id,Items._version,Items._url,Items._fileName,Items._fileSize
            68,1,https://cdn.ravensburger.de/db/applications/One.gme,"One, quoted",50
            68,2,https://cdn.ravensburger.de/db/applications/Two.gme,Two,60
            0,3,https://cdn.ravensburger.de/db/Firmware/update.bin,update,100
            77,1,https://evil.invalid/book.gme,Evil,50
            77,1,https://cdn.ravensburger.de/db/applications/Bad.gme,../Bad,50
        """.trimIndent()
        val entries = Catalog.parse(csv)
        assertEquals(2, entries.size)
        assertEquals(listOf(68, 68), entries.map { it.id })
        assertEquals("One, quoted.gme", entries.first().filename)
        assertNotEquals(entries[0].cacheKey, entries[1].cacheKey)
    }
    @Test fun rejectsCredentialsAndInsecureRedirectTargets() {
        assertFalse(Catalog.allowedUrl("http://cdn.ravensburger.de/a"))
        assertFalse(Catalog.allowedUrl("https://token@cdn.ravensburger.de/a"))
        assertFalse(Catalog.allowedUrl("https://cdn.ravensburger.de.evil.invalid/a"))
        assertTrue(Catalog.allowedUrl(Catalog.URL))
    }
}
