package io.github.kzahel.tiptoimanager

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class FileManagementTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Item(val name: String, val size: Long, val date: Long?)
    private val files = listOf(Item("Unknown", 200, null), Item("Zebra", 10, 100), Item("Apple", 30, 100), Item("New", 30, 200))
    private fun ordered(sort: PenSort) = sort.order(files, { it.name }, { it.size }, { it.date }).map { it.name }

    @Test fun dateSortsHaveStableTitleTiesAndUnknownDatesLast() {
        assertEquals(listOf("Apple", "Zebra", "New", "Unknown"), ordered(PenSort.OLDEST))
        assertEquals(listOf("New", "Apple", "Zebra", "Unknown"), ordered(PenSort.NEWEST))
        assertEquals(listOf("Unknown", "Apple", "New", "Zebra"), ordered(PenSort.LARGEST))
    }

    @Test fun installedBooksOfferRemovalRatherThanAnotherDownload() {
        for (downloaded in listOf(false, true)) for (backup in listOf(false, true)) {
            assertEquals(BookPrimaryAction.REMOVE, BookActions.primary(true, true, backup, downloaded, true, true, 0, 30))
            assertEquals(BookPrimaryAction.NONE, BookActions.primary(true, false, backup, downloaded, true, true, 0, 30))
        }
        assertFalse(BookActions.removable("WissenManage.gme", null))
        assertFalse(BookActions.removable("Any-support-file.gme", 14999))
        assertFalse(BookActions.removable("../book.gme", 1))
        assertTrue(BookActions.removable("A book.gme", 248))
    }

    @Test fun restorationNeedsConnectionAndSpaceButNotCatalogOrNetwork() {
        assertEquals(BookPrimaryAction.CONNECT, BookActions.primary(false, true, true, false, false, false, null, 30))
        assertEquals(BookPrimaryAction.MAKE_SPACE, BookActions.primary(false, true, true, false, true, false, 0, 30))
        assertEquals(BookPrimaryAction.RESTORE, BookActions.primary(false, true, true, false, true, false, null, 30))
        assertEquals(BookPrimaryAction.INSTALL, BookActions.primary(false, true, false, true, true, true, null, 30))
        assertEquals(BookPrimaryAction.DOWNLOAD, BookActions.primary(false, true, false, false, true, true, 0, 30))
    }

    @Test fun rawCopyChecksEveryByteAndRejectsShortOrGrowingSources() {
        val bytes = ByteArray(200_001) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val result = RawIntegrity.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong())
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(sha256(bytes), result.sha256)
        assertEquals(bytes.size.toLong(), result.bytes)
        assertThrows(IllegalStateException::class.java) { RawIntegrity.inspect(ByteArrayInputStream(bytes), bytes.size + 1L) }
        assertThrows(IllegalStateException::class.java) { RawIntegrity.inspect(ByteArrayInputStream(bytes), bytes.size - 1L) }
    }

    @Test fun backupsSurviveReloadWithOriginalDateAndPreferMatchingPen() {
        val root = temporary.newFolder()
        fun save(folder: String, pen: String, time: Long): RemovalBackup {
            val dir = File(root, folder).apply { mkdir() }
            File(dir, "content.gme").writeBytes(byteArrayOf(1, 2, 3))
            return RemovalBackup(dir, "Book.gme", 3, 248, 1234, pen, "b".repeat(64), time).also { it.saveManifest() }
        }
        val older = save("older", "a".repeat(64), 100)
        val newer = save("newer", "c".repeat(64), 200)
        File(root, "incomplete").mkdir()
        val store = RemovalBackups(root).apply { load() }
        assertEquals(2, store.all.size)
        assertEquals(older.directory, store.latest("BOOK.gme", older.fingerprint)?.directory)
        assertEquals(1234L, store.latest("Book.gme", older.fingerprint)?.sourceDate)
        assertEquals(newer.directory, store.latest("Book.gme")?.directory)
        assertEquals(1, store.latestCopies().size)
        newer.file.writeBytes(byteArrayOf(1))
        store.load()
        assertEquals(listOf(older.directory), store.all.map { it.directory })
    }

    @Test fun backupProvenanceUsesStableIdentityAndRetainsLegacyFingerprintMatching() {
        val root = temporary.newFolder()
        fun save(folder: String, identity: String?, fingerprint: String, time: Long): RemovalBackup {
            val dir = File(root, folder).apply { mkdir() }; File(dir, "content.gme").writeBytes(byteArrayOf(1))
            return RemovalBackup(dir, "Book.gme", 1, 248, null, fingerprint, "b".repeat(64), time, identity).also { it.saveManifest() }
        }
        val older = save("older", "serial:" + "a".repeat(64), "a".repeat(64), 100)
        save("newer", "serial:" + "c".repeat(64), "c".repeat(64), 200)
        val store = RemovalBackups(root).apply { load() }
        assertEquals(older.directory, store.latest("Book.gme", "d".repeat(64), older.identityKey)?.directory)
        assertEquals(older.directory, store.latest("Book.gme", "d".repeat(64), null, setOf(older.fingerprint))?.directory)
    }
}
