package io.github.kzahel.tiptoimanager

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneAudioTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(parent: File, name: String) = File(parent, name).apply { parent.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
    private fun store(root: File) = PhoneAudio(File(root, "downloads"), File(root, "temporary"), RemovalBackups(File(root, "backups")))

    @Test fun individualAndBulkCleanupStayWithinAudioRoots() {
        val root = temporary.newFolder()
        val store = store(root)
        val first = file(store.downloads, "first.gme")
        val second = file(store.downloads, "second.gme")
        val staged = file(store.temporary, "partial.gme")
        val backup = file(File(root, "backups/orphan"), "content.gme")
        val metadata = file(root, "catalog.csv")
        val artwork = file(File(root, "covers"), "cover.img")
        assertEquals(12L, store.usage().total)
        assertThrows(IllegalArgumentException::class.java) { store.deleteDownload(metadata) }
        store.deleteDownload(first)
        assertFalse(first.exists()); assertTrue(second.exists()); assertTrue(backup.exists())
        store.clearDownloads()
        assertFalse(second.exists()); assertTrue(backup.exists()); assertTrue(staged.exists())
        store.clearAll()
        assertFalse(staged.exists()); assertFalse(backup.exists())
        assertTrue(metadata.exists()); assertTrue(artwork.exists())
        assertEquals(0L, store.usage().total)
    }

    @Test fun deletesAllRevisionsOfOnlyTheSelectedBackupFilename() {
        val root = temporary.newFolder()
        val backups = RemovalBackups(File(root, "backups"))
        fun backup(folder: String, name: String): File {
            val content = file(File(backups.directory, folder), "content.gme")
            RemovalBackup(content.parentFile!!, name, 3, null, null, "a".repeat(64), "b".repeat(64), 123).saveManifest()
            return content
        }
        val one = backup("one", "Book.gme"); val two = backup("two", "BOOK.gme"); val other = backup("other", "Other.gme")
        backups.load()
        PhoneAudio(File(root, "downloads"), File(root, "temporary"), backups).deleteBackups("book.gme")
        assertFalse(one.exists()); assertFalse(two.exists()); assertTrue(other.exists())
        assertEquals(listOf("Other.gme"), backups.all.map { it.filename })
    }

    @Test fun temporarySourcesAreDeletedAfterSuccessAndFailure() {
        val root = temporary.newFolder()
        val success = file(root, "success.gme")
        AudioTransfers.temporary(success) { assertTrue(it.exists()) }
        assertFalse(success.exists())
        val failure = file(root, "failure.gme")
        assertThrows(IllegalStateException::class.java) {
            AudioTransfers.temporary(failure) { error("Simulated transfer failure") }
        }
        assertFalse(failure.exists())
    }

    @Test fun existingDownloadsAreDeletedOnlyAfterVerifiedSuccessInTransferMode() {
        val root = temporary.newFolder()
        val source = file(root, "source.gme")
        assertThrows(IllegalStateException::class.java) {
            AudioTransfers.install(source, AudioRetention.TRANSFER_ONLY) { error("Read-back verification failed") }
        }
        assertTrue(source.exists())
        assertTrue(AudioTransfers.install(source, AudioRetention.KEEP) { assertTrue(it.exists()) })
        assertTrue(source.exists())
        assertTrue(AudioTransfers.install(source, AudioRetention.TRANSFER_ONLY) { assertTrue(it.exists()) })
        assertFalse(source.exists())
    }

    @Test fun transferOnlyRequiresPenAndSpaceInsteadOfSavingOffline() {
        fun action(connected: Boolean, free: Long?) = BookActions.primary(false, true, false, false,
            connected, true, free, 15_000_000, true)
        assertEquals(BookPrimaryAction.CONNECT, action(false, null))
        assertEquals(BookPrimaryAction.MAKE_SPACE, action(true, 0))
        assertEquals(BookPrimaryAction.DOWNLOAD_INSTALL, action(true, 20_000_000))
        assertEquals(BookPrimaryAction.DOWNLOAD_INSTALL, action(true, null))
    }
}
