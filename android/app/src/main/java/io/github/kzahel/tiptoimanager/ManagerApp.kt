package io.github.kzahel.tiptoimanager

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

data class TransferInfo(val action: String, val bookKey: String, val stage: String = "Preparing",
    val percent: Int? = null, val running: Boolean = true, val failed: Boolean = false)

class ManagerApp : Application() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("manager", MODE_PRIVATE) }
    val storage by lazy { PenStorage(contentResolver) }
    val downloads by lazy { File(filesDir, "downloads").apply { mkdirs() } }
    var tree: Uri? = null
        private set
    var pen: PenSnapshot? = null
        private set
    var catalog: List<CatalogEntry> = emptyList()
        private set
    var busy = false
        private set
    var status = "Connect a pen to get started."
        private set
    var failure = false
        private set
    var changed: (() -> Unit)? = null
    var progressed: (() -> Unit)? = null
    var transfer: TransferInfo? = null
        private set
    private var lastProgress = 0L

    override fun onCreate() {
        super.onCreate()
        val detached = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (pen != null) {
                    pen = null
                    status = "USB disconnected. Reconnect the pen and scan again."
                    changed?.invoke()
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(detached, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(detached, filter)
        tree = preferences.getString("tree", null)?.let(Uri::parse)
        // A process interrupted during download never leaves a completed cache file.
        downloads.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        work("Loading local library") {
            val local = File(filesDir, "catalog.csv")
            if (local.exists()) catalog = Catalog.parse(local.readText())
            status = if (catalog.isEmpty()) "Refresh the catalog to browse books." else "Catalog loaded from this phone."
            tree?.let { pen = storage.scan(it); status = "Pen connected. ${pen!!.files.size} content files." }
        }
    }

    fun selectTree(value: Uri) {
        tree = value
        preferences.edit().putString("tree", value.toString()).apply()
        refreshPen()
    }

    fun refreshPen() = work("Reading pen") {
        pen = null
        pen = storage.scan(tree ?: error("Choose the pen folder first."))
        status = "Pen scanned. ${pen!!.files.size} files · ${pen!!.ids} product IDs."
    }

    fun refreshCatalog() = work("Downloading catalog") {
        val partial = File(filesDir, "catalog.part")
        try {
            Downloads.fetch(Catalog.URL, partial)
            val parsed = Catalog.parse(partial.readText())
            check(parsed.isNotEmpty()) { "The catalog contained no supported content." }
            check(partial.renameTo(File(filesDir, "catalog.csv"))) { "Could not save the catalog." }
            catalog = parsed
            status = "Catalog refreshed. ${catalog.size} content files, including companion files."
        } finally { partial.delete() }
    }

    fun cache(entry: CatalogEntry): File = File(downloads, entry.cacheKey)
    fun cached(entry: CatalogEntry): Boolean = cache(entry).let { it.isFile && it.length() == entry.size }

    fun download(entry: CatalogEntry) = work("Download", entry) {
        val partial = File(downloads, entry.cacheKey + ".part")
        try {
            Downloads.fetch(entry.url, partial, entry.size) { progress("Downloading", it, entry.size) }
            progress("Checking download", 0, entry.size)
            partial.inputStream().buffered().use { Gme.inspect(it, entry.size, entry.id) }
            check(partial.renameTo(cache(entry))) { "Could not save the verified download." }
            status = "Saved offline: ${entry.title}. Size, product ID and checksum verified."
        } finally { partial.delete() }
    }

    fun install(entry: CatalogEntry) = work("Install", entry) {
        val selected = pen ?: error("Scan the connected pen first.")
        storage.install(selected, entry, cache(entry)) { stage, bytes -> progress(stage, bytes, entry.size) }
        pen = storage.scan(selected.tree)
        status = "Installed and read-back verified: ${entry.title}. Unplug and tap the book to check playback."
    }

    private fun progress(stage: String, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgress < 250 && bytes != 0L && bytes != total) return
        lastProgress = now
        main.post {
            val percent = (bytes * 100 / total).coerceIn(0, 100).toInt()
            status = "$stage · $percent%"
            transfer?.takeIf { it.running }?.let { transfer = it.copy(stage = stage, percent = percent) }
            progressed?.invoke()
        }
    }

    private fun work(label: String, book: CatalogEntry? = null, action: () -> Unit) {
        if (busy) return
        busy = true; status = label; failure = false
        if (book != null) transfer = TransferInfo(label, book.cacheKey)
        changed?.invoke()
        executor.execute {
            var error: String? = null
            try { action() } catch (e: Exception) { error = e.message ?: "Operation failed. Reconnect and try again." }
            main.post {
                if (error != null) { status = error; failure = true }
                if (book != null) transfer = transfer?.copy(running = false, failed = error != null,
                    stage = error ?: if (label == "Download") "Saved offline" else "Read-back verified", percent = null)
                busy = false; changed?.invoke()
            }
        }
    }
}
