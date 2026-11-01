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
