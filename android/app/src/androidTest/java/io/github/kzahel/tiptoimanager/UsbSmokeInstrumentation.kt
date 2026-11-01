package io.github.kzahel.tiptoimanager

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

/** Explicit, opt-in real-device checks; no UI input and no writes by default. */
class UsbSmokeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            val app = targetContext.applicationContext as ManagerApp
            waitIdle(app)
            arguments.getString("metadataProductId")?.toInt()?.let { id ->
                val offline = arguments.getString("metadataCachedOnly") == "true"
                if (!offline) operation(app) { app.refreshCatalog() }
                val entry = app.catalog.single { it.id == id }
                val book = app.book(entry.filename, entry.id) ?: error("No unambiguous publisher metadata matched the audio.")
                val details = if (offline) app.metadata.page(book.article) ?: error("Publisher details are not cached.")
                    else app.metadata.fetchPage(book)
                check(details.isbn != null && BookMetadataParser.validIsbn(details.isbn)) { "No validated publisher ISBN." }
                for (url in listOfNotNull(book.thumbUrl, details.coverUrl ?: book.coverUrl).distinct()) {
                    val cache = app.covers.file(url)
                    if (!offline) atomicWrite(cache, PublisherHttp.get(url, 5_000_000, PublisherUrls::image))
                    check(cache.exists()) { "Cover was not cached." }
                    val bitmap = android.graphics.BitmapFactory.decodeFile(cache.path)
                    check(bitmap != null && bitmap.width > 0 && bitmap.height > 0) { "Cover did not decode." }
                    bitmap.recycle()
                }
                results.putBoolean("publisherMetadataMatched", true)
                results.putBoolean("publisherIsbnValidated", true)
                results.putBoolean("cachedCoversDecoded", true)
                results.putBoolean("cachedOnly", offline)
                results.putString("stream", "Publisher metadata and covers passed${if (offline) " from cache without network requests" else " using direct publisher requests"}.\n")
                finish(Activity.RESULT_OK, results)
                return
            }
            val tree = app.tree ?: error("Grant the pen's folder through the app before running this check.")
            val original = app.storage.scan(tree)
            arguments.getString("cacheFixtureProductId")?.toInt()?.let { id ->
                cacheFixture(app, original, id, results)
                finish(Activity.RESULT_OK, results)
                return
            }
            arguments.getString("removalFixtureProductId")?.toInt()?.let { id ->
                removalFixture(app, original, id, results)
                finish(Activity.RESULT_OK, results)
                return
            }
            results.putInt("inventoryFiles", original.files.size)
            results.putInt("inventoryProductIds", original.ids)
            if (arguments.getString("refreshCatalog") == "true") operation(app) { app.refreshCatalog() }
            results.putInt("catalogEntries", app.catalog.size)
            val downloadId = arguments.getString("downloadProductId")?.toInt()
            val installId = arguments.getString("installProductId")?.toInt()
            check(installId == null || installId == downloadId) { "Installing requires the same explicit downloadProductId." }
            if (downloadId != null) {
                val matches = app.catalog.filter { it.id == downloadId }
                check(matches.size == 1) { "Choose a standalone product with exactly one catalog file for this spike test." }
                val entry = matches.single()
                operation(app) { app.download(entry) }
                val downloaded = app.cache(entry).inputStream().buffered().use { Gme.inspect(it, entry.size, entry.id) }
                results.putBoolean("downloadVerified", downloaded.id == downloadId)
                if (installId != null) {
                    check(original.files.none { it.name.equals(entry.filename, true) }) { "The selected file is already present; this runner never overwrites." }
                    operation(app) { app.refreshPen() }
                    check(app.pen?.fingerprint == original.fingerprint) { "The selected pen changed." }
                    operation(app) { app.install(entry) }
                    val after = app.storage.scan(tree)
                    check(after.fingerprint == original.fingerprint)
                    val installed = after.files.single { it.name == entry.filename }
                    val readBack = targetContext.contentResolver.openInputStream(installed.uri)!!.buffered().use {
                        Gme.inspect(it, entry.size, entry.id)
                    }
                    check(readBack.sha256 == downloaded.sha256)
                    check(after.files.filter { it.name != entry.filename }.map { Triple(it.name, it.size, it.id) }.toSet() ==
                        original.files.map { Triple(it.name, it.size, it.id) }.toSet()) { "Unexpected inventory change." }
                    results.putBoolean("installReadBackVerified", true)
                    results.putBoolean("priorInventoryPreserved", true)
                }
            }
            results.putString("stream", "USB smoke passed. Playback still requires the physical book.\n")
            finish(Activity.RESULT_OK, results)
        } catch (e: Exception) {
            results.putString("stream", "USB smoke failed: ${e.message}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    /** Create, remove and restore only our uniquely named disposable copy. Never delete a prior book. */
    private fun removalFixture(app: ManagerApp, original: PenSnapshot, id: Int, results: Bundle) {
        val entry = app.catalog.single { it.id == id }
        check(app.cached(entry)) { "Cache the explicitly selected product before this offline test." }
        val name = "Tiptoi manager removal test ${java.util.UUID.randomUUID()}.gme"
        val inventory = original.files.map { Triple(it.name, it.size, it.id) }.toSet()
        var backup: RemovalBackup? = null
        try {
            app.storage.install(original, entry.copy(filename = name), app.cache(entry)) { _, _ -> }
            operation(app) { app.refreshPen() }
            val fixture = app.pen!!.files.single { it.name == name }
            check(fixture.modifiedAt != null) { "Provider did not expose the fixture's file date." }
            operation(app) { app.remove(fixture) }
            check(app.pen!!.files.none { it.name == name })
            check(app.pen!!.files.map { Triple(it.name, it.size, it.id) }.toSet() == inventory)
            val reloaded = RemovalBackups(app.backups.directory).apply { load() }
            backup = reloaded.latest(name, original.fingerprint) ?: error("Durable backup missing after removal.")
            check(backup.sourceDate == fixture.modifiedAt)
            val source = app.cache(entry).inputStream().use { RawIntegrity.inspect(it, entry.size) }
            check(backup.sha256 == source.sha256)
            operation(app) { app.restore(backup) }
            val restored = app.pen!!.files.single { it.name == name }
            val checked = targetContext.contentResolver.openInputStream(restored.uri)!!.use { RawIntegrity.inspect(it, restored.size) }
            check(checked.sha256 == source.sha256)
            assertExistingDestinationRefused(app, backup)
            results.putBoolean("verifiedBackupPersisted", true)
            results.putBoolean("offlineRestoreReadBackVerified", true)
            results.putBoolean("existingDestinationPreserved", true)
            results.putBoolean("fileDatesAvailable", true)
        } finally {
            val current = app.storage.scan(original.tree)
            check(current.fingerprint == original.fingerprint) { "Pen changed; retain fixture backup for recovery." }
            // Exact unique test filename only; no caller-supplied original filename can reach this delete.
            current.files.singleOrNull { it.name == name }?.let {
                check(android.provider.DocumentsContract.deleteDocument(targetContext.contentResolver, it.uri))
            }
            val after = app.storage.scan(original.tree)
            check(after.fingerprint == original.fingerprint)
            check(after.files.map { Triple(it.name, it.size, it.id) }.toSet() == inventory) { "Prior inventory changed." }
            backup?.directory?.deleteRecursively()
            app.backups.load()
            operation(app) { app.refreshPen() }
        }
        results.putBoolean("priorInventoryPreserved", true)
        results.putString("stream", "Disposable copy: backup, removal, offline restore, no-overwrite and cleanup passed; prior inventory preserved.\n")
    }

    private fun assertExistingDestinationRefused(app: ManagerApp, backup: RemovalBackup) {
        var refused = false
        try { app.storage.restore(app.pen!!, backup) { _, _ -> } }
        catch (e: IllegalStateException) { refused = e.message?.contains("already on the pen") == true }
        check(refused) { "Restoration must refuse an existing destination." }
    }

    /** Reuse the selected book's bytes under a unique filename/key; never clean up existing household audio. */
    private fun cacheFixture(app: ManagerApp, original: PenSnapshot, id: Int, results: Bundle) {
        val entry = app.catalog.single { it.id == id }
        check(app.cached(entry)) { "Cache the selected source book before this test." }
        val token = java.util.UUID.randomUUID().toString()
        val fixture = entry.copy(filename = "Tiptoi manager cache test $token.gme", version = "cache-test-$token")
        val source = app.cache(entry).inputStream().use { Gme.inspect(it, entry.size, id) }
        val cache = app.cache(fixture)
        val initialDownloads = app.phoneAudio.downloadFiles().map { it.name }.toSet()
        val initialBackups = app.backups.all.map { it.directory.name }.toSet()
        val previousMode = app.audioRetention
        val inventory = original.files.map { Triple(it.name, it.size, it.id) }.toSet()
        fun deleteOwnPenCopy() {
            val pen = app.storage.scan(original.tree)
            check(pen.fingerprint == original.fingerprint)
            pen.files.singleOrNull { it.name == fixture.filename }?.let {
                check(android.provider.DocumentsContract.deleteDocument(targetContext.contentResolver, it.uri))
            }
        }
        fun checkOwnPenCopy() {
            val installed = app.storage.scan(original.tree).files.single { it.name == fixture.filename }
            val digest = targetContext.contentResolver.openInputStream(installed.uri)!!.use { Gme.inspect(it, fixture.size, id) }
            check(digest.sha256 == source.sha256)
        }
        try {
            app.cache(entry).copyTo(cache)
            runOnMainSync { app.updateAudioRetention(AudioRetention.TRANSFER_ONLY) }
            operation(app) { app.install(fixture) }
            check(!cache.exists()) { "Transfer-only install retained its downloaded source." }
            checkOwnPenCopy()
            deleteOwnPenCopy()
            operation(app) { app.refreshPen() }
            operation(app) { app.downloadAndInstall(fixture) }
            checkOwnPenCopy()
            check(!cache.exists() && app.phoneAudio.temporary.listFiles().orEmpty().isEmpty())
            val local = java.io.File(app.filesDir, "cache-smoke-$token")
            try {
                val backups = RemovalBackups(java.io.File(local, "backups"))
                val store = PhoneAudio(java.io.File(local, "downloads"), java.io.File(local, "temporary"), backups)
                store.downloads.mkdirs(); store.temporary.mkdirs()
                val first = java.io.File(store.downloads, "first.gme").apply { writeText("fixture") }
                val second = java.io.File(store.downloads, "second.gme").apply { writeText("fixture") }
                val backup = java.io.File(backups.directory, "orphan/content.gme").apply { parentFile!!.mkdirs(); writeText("backup") }
                store.deleteDownload(first); check(!first.exists() && second.exists())
                store.clearDownloads(); check(!second.exists() && backup.exists())
                store.clearAll(); check(!backup.exists() && store.usage().total == 0L)
            } finally { local.deleteRecursively() }
            results.putBoolean("transferOnlyInstallDeletedSource", true)
            results.putBoolean("downloadAndInstallReadBackVerified", true)
            results.putBoolean("temporaryAudioRemoved", true)
            results.putBoolean("phoneCleanupFixturesPassed", true)
        } finally {
            runOnMainSync { app.updateAudioRetention(previousMode) }
            deleteOwnPenCopy()
            cache.delete()
            operation(app) { app.refreshPen() }
            val after = app.pen!!
            check(after.files.map { Triple(it.name, it.size, it.id) }.toSet() == inventory)
            check(app.phoneAudio.downloadFiles().map { it.name }.toSet() == initialDownloads)
            check(app.backups.all.map { it.directory.name }.toSet() == initialBackups)
            check(app.cache(entry).inputStream().use { Gme.inspect(it, entry.size, id) }.sha256 == source.sha256)
        }
        results.putBoolean("priorPenAndPhoneFilesPreserved", true)
        results.putString("stream", "Cache fixture: verified installs, temporary cleanup, phone deletion scopes and original inventories passed.\n")
    }

    private fun operation(app: ManagerApp, action: () -> Unit) {
        waitIdle(app)
        runOnMainSync { action() }
        waitIdle(app)
        var error: String? = null
        runOnMainSync { if (app.failure) error = app.status }
        check(error == null) { error ?: "Operation failed." }
    }

    private fun waitIdle(app: ManagerApp) {
        val deadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000
        while (true) {
            var busy = true
            runOnMainSync { busy = app.busy }
            if (!busy) return
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for the app operation." }
            SystemClock.sleep(100)
        }
    }
}
