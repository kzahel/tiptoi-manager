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

data class TransferInfo(val action: String, val bookKey: String?, val filename: String, val title: String, val stage: String = "Preparing",
    val percent: Int? = null, val running: Boolean = true, val failed: Boolean = false)

class ManagerApp : Application() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val detailsWorker = Executors.newFixedThreadPool(2)
    private val preferences by lazy { getSharedPreferences("manager", MODE_PRIVATE) }
    val storage by lazy { PenStorage(contentResolver) }
    val downloads by lazy { File(filesDir, "downloads").apply { mkdirs() } }
    val backups by lazy { RemovalBackups(File(filesDir, "pen-backups")) }
    val phoneAudio by lazy { PhoneAudio(downloads, File(filesDir, "transfer-audio"), backups) }
    var audioRetention: AudioRetention
        get() = runCatching { AudioRetention.valueOf(preferences.getString("audioRetention", "KEEP")!!) }.getOrDefault(AudioRetention.KEEP)
        private set(value) { preferences.edit().putString("audioRetention", value.name).apply() }
    var audioUsage = AudioUsage(0, 0, 0)
        private set
    val metadata by lazy { BookMetadataStore(File(filesDir, "book-metadata"), BuildConfig.CATALOG_CLIENT_ID, BuildConfig.CATALOG_CLIENT_SECRET) }
    val covers by lazy { CoverImages(File(filesDir, "covers")) }
    var metadataChanged: ((String) -> Unit)? = null
    var metadataNotice: String? = null
        private set
    val pendingPublisherPages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
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
            phoneAudio.clearTemporary()
            backups.load()
            metadata.loadCached()
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
            metadataNotice = runCatching { metadata.refresh() }.exceptionOrNull()?.message
            main.post { covers.clearFailures() }
            status = "Catalog refreshed. ${catalog.size} content files, including companion files."
        } finally { partial.delete() }
    }

    fun cache(entry: CatalogEntry): File = File(downloads, entry.cacheKey)
    fun cached(entry: CatalogEntry): Boolean = cache(entry).let { it.isFile && it.length() == entry.size }
    fun backup(filename: String): RemovalBackup? = backups.latest(filename, pen?.fingerprint)

    fun book(filename: String, id: Int?): BookInfo? {
        if (id == 14999) return null // Shared support content is not an individual book.
        val candidates = metadata.forFile(filename, id)
        return candidates.firstOrNull()?.takeIf { candidates.map { book -> book.title }.distinct().size == 1 }
    }

    fun title(entry: CatalogEntry): String = book(entry.filename, entry.id)?.title ?: entry.title

    fun requestPublisherDetails(book: BookInfo) {
        if (metadata.pageFresh(book.article) || book.article in pendingPublisherPages ||
            !metadata.attemptedPages.add(book.article)) return
        pendingPublisherPages.add(book.article)
        detailsWorker.execute {
            runCatching { metadata.fetchPage(book) }
            pendingPublisherPages.remove(book.article)
            main.post { metadataChanged?.invoke(book.article) }
        }
    }

    fun download(entry: CatalogEntry) = work("Download", entry) {
        check(audioRetention == AudioRetention.KEEP) { "Transfer only is selected. Connect a pen and choose Download & install." }
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
        val cleaned = AudioTransfers.install(cache(entry), audioRetention) { source ->
            storage.install(selected, entry, source) { stage, bytes -> progress(stage, bytes, entry.size) }
        }
        pen = storage.scan(selected.tree)
        status = "Installed and read-back verified: ${entry.title}. Unplug and tap the book to check playback."
        if (!cleaned) completionNote = "Installed and verified · phone cleanup failed; use Phone storage"
        else if (audioRetention == AudioRetention.TRANSFER_ONLY) completionNote = "Installed and verified · phone download deleted"
    }

    fun downloadAndInstall(entry: CatalogEntry) = work("Install", entry) {
        val selected = pen ?: error("Scan the connected pen first.")
        val fresh = storage.scan(selected.tree)
        check(fresh.fingerprint == selected.fingerprint) { "The connected pen changed. Scan again." }
        check(fresh.files.none { it.name.equals(entry.filename, true) }) { "This filename is already on the pen." }
        check(fresh.free == null || fresh.free >= entry.size + 1_048_576) { "The pen does not have enough free space." }
        phoneAudio.temporary.mkdirs()
        val temporary = File(phoneAudio.temporary, entry.cacheKey)
        AudioTransfers.temporary(temporary) { source ->
            Downloads.fetch(entry.url, source, entry.size) { progress("Downloading temporary copy", it, entry.size) }
            storage.install(fresh, entry, source) { stage, bytes -> progress(stage, bytes, entry.size) }
        }
        pen = storage.scan(selected.tree)
        completionNote = "Installed and verified · temporary phone copy deleted"
        status = "Installed and verified: ${entry.title}. Temporary phone copy deleted."
    }

    fun updateAudioRetention(value: AudioRetention) {
        if (busy) return
        audioRetention = value
        changed?.invoke()
    }

    fun deletePhoneDownload(file: File) = work("Deleting phone download") {
        phoneAudio.deleteDownload(file)
        status = "Phone download deleted."
    }

    fun deletePhoneBackups(filename: String) = work("Deleting phone backups") {
        phoneAudio.deleteBackups(filename)
        status = "Phone backups deleted."
    }

    fun clearPhoneAudio(includeBackups: Boolean) = work("Clearing phone audio") {
        if (includeBackups) phoneAudio.clearAll() else { phoneAudio.clearDownloads(); phoneAudio.clearTemporary() }
        status = if (includeBackups) "All phone audio deleted." else "Phone downloads deleted. Pen backups kept."
    }

    fun remove(file: PenFile) = work("Remove", filename = file.name,
        localTitle = book(file.name, file.id)?.title ?: file.name) {
        val selected = pen ?: error("Scan the connected pen first.")
        try {
            storage.remove(selected, file, backups.directory) { stage, bytes -> progress(stage, bytes, file.size) }
            status = "Removed from pen. A verified backup is saved on this phone."
        } finally {
            backups.load()
            pen = runCatching { storage.scan(selected.tree) }.getOrNull()
        }
    }

    fun restore(backup: RemovalBackup) = work("Restore", filename = backup.filename,
        localTitle = book(backup.filename, backup.audioId)?.title ?: backup.filename) {
        val selected = pen ?: error("Scan the connected pen first.")
        try {
            storage.restore(selected, backup) { stage, bytes -> progress(stage, bytes, backup.size) }
            status = "Restored and read-back verified. Phone backup retained."
        } finally { pen = runCatching { storage.scan(selected.tree) }.getOrNull() }
    }

    private fun progress(stage: String, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgress < 250 && bytes != 0L && bytes != total) return
        lastProgress = now
        main.post {
            val percent = if (total > 0) (bytes * 100 / total).coerceIn(0, 100).toInt() else 0
            status = "$stage · $percent%"
            transfer?.takeIf { it.running }?.let { transfer = it.copy(stage = stage, percent = percent) }
            progressed?.invoke()
        }
    }

    private var completionNote: String? = null
    private fun work(label: String, book: CatalogEntry? = null, filename: String? = book?.filename,
                     localTitle: String? = book?.let(::title), action: () -> Unit) {
        if (busy) return
        busy = true; status = label; failure = false; completionNote = null
        if (filename != null) transfer = TransferInfo(label, book?.cacheKey, filename, localTitle ?: filename)
        changed?.invoke()
        executor.execute {
            var error: String? = null
            try { action() } catch (e: Exception) { error = e.message ?: "Operation failed. Reconnect and try again." }
            try { audioUsage = phoneAudio.usage() } catch (e: Exception) { if (error == null) error = e.message }
            main.post {
                if (error != null) { status = error; failure = true }
                if (filename != null) transfer = transfer?.copy(running = false, failed = error != null,
                    stage = error ?: completionNote ?: when (label) {
                        "Download" -> "Saved offline"
                        "Remove" -> "Removed from pen · phone backup kept"
                        else -> "Read-back verified"
                    }, percent = null)
                busy = false; changed?.invoke()
            }
        }
    }
}
