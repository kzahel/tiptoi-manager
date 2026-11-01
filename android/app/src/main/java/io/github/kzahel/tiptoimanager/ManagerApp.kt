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

data class TransferInfo(val action: String, val bookKey: String?, val filename: String, val title: String, val stage: String = t(R.string.msg_preparing),
    val percent: Int? = null, val running: Boolean = true, val failed: Boolean = false, val target: String? = null)

class ManagerApp : Application() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val detailsWorker = Executors.newFixedThreadPool(2)
    private val preferences by lazy { getSharedPreferences("manager", MODE_PRIVATE) }
    val profiles by lazy { PenProfiles(File(filesDir, "pens.json")) }
    var selectedProfileId: String? = null
        private set
    var demoMode = false
        private set
    private var realSelection: String? = null
    val knownProfiles: List<PenProfile> get() = if (demoMode) DemoLibrary.pens else profiles.all
    val familySelection: List<CatalogEntry> get() = if (demoMode) DemoLibrary.entries else profiles.family
    fun targets(profile: PenProfile): List<CatalogEntry> = if (demoMode) DemoLibrary.entries else profiles.targets(profile)
    val displayConnection: PenSnapshot? get() = if (demoMode) DemoLibrary.connected else pen
    fun setDemo(enabled: Boolean) {
        if (busy || enabled == demoMode) return
        connectionEpoch++
        if (enabled) { realSelection = selectedProfileId; selectedProfileId = DemoLibrary.pens.first().id }
        else selectedProfileId = realSelection
        demoMode = enabled
        failure = false
        changed?.invoke()
    }
    val viewedProfile: PenProfile? get() = knownProfiles.find { it.id == selectedProfileId }
    val viewedPen: PenSnapshot? get() = displayConnection?.takeIf { it.identity.key == viewedProfile?.identity?.key }
    val visibleFiles: List<SavedPenFile> get() = viewedProfile?.files.orEmpty()
    val connectedName: String? get() = displayConnection?.let { live -> knownProfiles.find { it.identity.key == live.identity.key }?.name }
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
    private var realCatalog: List<CatalogEntry> = emptyList()
    var catalog: List<CatalogEntry>
        get() = if (demoMode) DemoLibrary.entries else realCatalog
        private set(value) { realCatalog = value }
    @Volatile var busy = false
        private set
    var status = t(R.string.msg_connect_a_pen_to_get_started)
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
        UiText.initialize(this)
        val usbChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                connectionEpoch++
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    pen = null
                    status = t(R.string.msg_usb_disconnected_saved_inventories_are_still_available)
                    changed?.invoke()
                }
                probeConnection()
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(usbChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbChanged, filter)
        tree = preferences.getString("tree", null)?.let(Uri::parse)
        selectedProfileId = preferences.getString("selectedProfile", null)
        // A process interrupted during download never leaves a completed cache file.
        downloads.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        work("Loading local library") {
            profiles.load()
            if (viewedProfile == null) selectedProfileId = profiles.all.firstOrNull()?.id
            phoneAudio.clearTemporary()
            backups.load()
            metadata.loadCached()
            val local = File(filesDir, "catalog.csv")
            if (local.exists()) catalog = Catalog.parse(local.readText())
            status = if (catalog.isEmpty()) t(R.string.msg_refresh_the_catalog_to_browse_books) else t(R.string.msg_catalog_loaded_from_this_phone)
            discover()?.let { record(it, true); status = t(R.string.ui_connected_sentence, connectedName) }
        }
    }

    fun selectTree(value: Uri) = work("Reading pen") { record(storage.scan(value), true) }

    fun refreshPen() = work("Reading pen") {
        pen = null
        val snapshot = discover() ?: error(t(R.string.error_no_pen_found_in_the_granted_usb_folders_connect_a_pen_or_choose_its))
        record(snapshot, true)
        status = p(R.plurals.msg_1_s_scanned_2_d_files, snapshot.files.size, connectedName, snapshot.files.size)
    }

    private fun candidates(): List<Uri> = (listOfNotNull(tree) + profiles.all.map { Uri.parse(it.tree) } +
        contentResolver.persistedUriPermissions.filter { it.isReadPermission && it.isWritePermission }.map { it.uri }).distinct()

    private fun discover(): PenSnapshot? = candidates().firstNotNullOfOrNull { runCatching { storage.scan(it) }.getOrNull() }

    private fun record(snapshot: PenSnapshot, select: Boolean = pen?.identity?.key != snapshot.identity.key) {
        val profile = profiles.observe(snapshot.identity, snapshot.fingerprint, snapshot.tree.toString(), snapshot.files.map {
            SavedPenFile(it.name, it.size, it.id, it.problem, it.modifiedAt)
        }, snapshot.free, System.currentTimeMillis())
        pen = snapshot; tree = snapshot.tree
        preferences.edit().putString("tree", snapshot.tree.toString()).apply()
        if (select || viewedProfile == null) selectProfile(profile.id, notify = false)
    }

    fun selectProfile(id: String, notify: Boolean = true) {
        if (busy && notify) return
        require(knownProfiles.any { it.id == id })
        selectedProfileId = id
        if (!demoMode) preferences.edit().putString("selectedProfile", id).apply()
        if (notify) changed?.invoke()
    }

    fun renameProfile(id: String, name: String) = work("Naming pen") { profiles.rename(id, name) }
    fun forgetProfile(id: String) = work("Forgetting pen") {
        val profile = profiles.all.find { it.id == id } ?: error(t(R.string.error_pen_profile_not_found))
        check(pen?.identity?.key != profile.identity.key) { t(R.string.error_disconnect_this_pen_before_forgetting_its_profile) }
        profiles.forget(id)
        if (selectedProfileId == id) {
            selectedProfileId = profiles.all.firstOrNull { it.identity.key == pen?.identity?.key }?.id ?: profiles.all.firstOrNull()?.id
            preferences.edit().putString("selectedProfile", selectedProfileId).apply()
        }
    }
    fun setFamilyEnabled(id: String, enabled: Boolean) = work("Saving pen selection") { profiles.useFamily(id, enabled) }
    fun selectContent(entry: CatalogEntry, profileId: String?, enabled: Boolean) = work("Saving book selection") {
        profiles.select(entry, profileId, enabled)
    }
    fun setContentTargets(entry: CatalogEntry, profileIds: Set<String>, includeFamily: Boolean) = work("Saving book selection") {
        profiles.setTargets(entry, profileIds, includeFamily)
    }
    fun plan(): PenPlan? = viewedProfile?.let { PenPlan.create(it, targets(it)) }

    private fun destination(expectedProfileId: String?): PenSnapshot {
        check(!demoMode) { t(R.string.ui_demo_readonly) }
        check(expectedProfileId != null && selectedProfileId == expectedProfileId) { t(R.string.error_the_selected_pen_changed_review_the_destination_again) }
        return viewedPen ?: error(t(R.string.error_connect_1_s_and_scan_it_before_writing, viewedProfile?.name ?: t(R.string.ui_selected_pen)))
    }

    private var watching = false
    private var probing = false
    @Volatile private var connectionEpoch = 0L
    private val watch = object : Runnable {
        override fun run() { if (watching) { probeConnection(); main.postDelayed(this, 3000) } }
    }
    fun startConnectionWatch() { if (!watching) { watching = true; main.post(watch) } }
    fun stopConnectionWatch() { watching = false; main.removeCallbacks(watch) }
    private fun probeConnection() {
        if (demoMode || busy || probing) return
        probing = true
        val epoch = connectionEpoch
        executor.execute {
            try {
                if (!demoMode && !busy) {
                    val match = candidates().firstNotNullOfOrNull { uri -> runCatching { uri to storage.identify(uri).first }.getOrNull() }
                    if (!demoMode && !busy && epoch == connectionEpoch) {
                        if (match == null && pen != null) { pen = null; main.post { changed?.invoke() } }
                        else if (match != null && (pen?.tree != match.first || pen?.fingerprint != match.second)) {
                            val snapshot = storage.scan(match.first)
                            if (!demoMode && !busy && epoch == connectionEpoch) { record(snapshot, true); main.post { changed?.invoke() } }
                        }
                    }
                }
            } catch (_: Exception) { /* Keep saved inventories; the explicit Scan action reports errors. */ }
            finally { main.post { probing = false } }
        }
    }

    fun refreshCatalog() = work("Downloading catalog") {
        val partial = File(filesDir, "catalog.part")
        try {
            Downloads.fetch(Catalog.URL, partial)
            val parsed = Catalog.parse(partial.readText())
            check(parsed.isNotEmpty()) { t(R.string.error_the_catalog_contained_no_supported_content) }
            check(partial.renameTo(File(filesDir, "catalog.csv"))) { t(R.string.error_could_not_save_the_catalog) }
            catalog = parsed
            metadataNotice = runCatching { metadata.refresh() }.exceptionOrNull()?.message
            main.post { covers.clearFailures() }
            status = p(R.plurals.msg_catalog_refreshed_1_d_content_files_including_companion_files, catalog.size, catalog.size)
        } finally { partial.delete() }
    }

    fun cache(entry: CatalogEntry): File = File(downloads, entry.cacheKey)
    fun cached(entry: CatalogEntry): Boolean = !demoMode && cache(entry).let { it.isFile && it.length() == entry.size }
    fun backup(filename: String): RemovalBackup? = if (demoMode) null else backups.latest(filename, viewedProfile?.fingerprint, viewedProfile?.identity?.key, viewedProfile?.fingerprints.orEmpty())

    fun book(filename: String, id: Int?): BookInfo? {
        if (demoMode) return DemoLibrary.book(filename)
        if (id == 14999) return null // Shared support content is not an individual book.
        val candidates = metadata.forFile(filename, id)
        return candidates.firstOrNull()?.takeIf { candidates.map { book -> book.title }.distinct().size == 1 }
    }

    fun title(entry: CatalogEntry): String = book(entry.filename, entry.id)?.title ?: entry.title

    fun requestPublisherDetails(book: BookInfo) {
        if (demoMode) return
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
        check(audioRetention == AudioRetention.KEEP) { t(R.string.error_transfer_only_is_selected_connect_a_pen_and_choose_download_install) }
        fetchToCache(entry)
        status = t(R.string.msg_saved_offline_1_s_size_product_id_and_checksum_verified, entry.title)
    }

    private fun fetchToCache(entry: CatalogEntry) {
        val partial = File(downloads, entry.cacheKey + ".part")
        try {
            Downloads.fetch(entry.url, partial, entry.size) { progress(t(R.string.msg_downloading), it, entry.size) }
            progress(t(R.string.msg_checking_download), 0, entry.size)
            partial.inputStream().buffered().use { Gme.inspect(it, entry.size, entry.id) }
            check(partial.renameTo(cache(entry))) { t(R.string.error_could_not_save_the_verified_download) }
        } finally { partial.delete() }
    }

    private fun rescanAfterWrite(tree: Uri) {
        val snapshot = runCatching { storage.scan(tree) }.getOrNull()
        if (snapshot == null) pen = null else record(snapshot)
    }

    fun applySelection(plan: PenPlan) = work("Sync", filename = "@selection", localTitle = t(R.string.msg_selected_books), target = viewedProfile?.name) {
        val selected = destination(plan.profileId)
        try {
            val fresh = storage.scan(selected.tree)
            plan.requireDestination(plan.profileId, fresh.identity.key, fresh.fingerprint)
            record(fresh, false)
            check(fresh.identity.hasSerial) { t(R.string.error_this_pen_s_serial_could_not_be_read_collection_sync_needs_a_recogniz) }
            val current = this.plan() ?: error(t(R.string.error_select_a_pen_first))
            check(current.items == plan.items) { t(R.string.error_the_pen_inventory_or_selection_changed_review_the_plan_again) }
            check(current.conflicts == 0) { t(R.string.error_resolve_conflicting_filenames_before_applying_this_selection) }
            check(fresh.free == null || fresh.free >= current.bytes + 1_048_576) { t(R.string.error_the_selected_books_do_not_fit_free_space_and_review_again) }
            var cleanupWarning = false
            for ((index, entry) in current.additions.withIndex()) {
                val report: (String, Long) -> Unit = { stage, bytes -> progress("${index + 1}/${current.additions.size} · $stage", bytes, entry.size) }
                val snapshot = pen ?: error(t(R.string.error_the_pen_disconnected))
                if (cached(entry) || audioRetention == AudioRetention.KEEP) {
                    if (!cached(entry)) fetchToCache(entry)
                    if (!AudioTransfers.install(cache(entry), audioRetention) { storage.install(snapshot, entry, it, report) }) cleanupWarning = true
                } else {
                    phoneAudio.temporary.mkdirs()
                    AudioTransfers.temporary(File(phoneAudio.temporary, entry.cacheKey)) { source ->
                        Downloads.fetch(entry.url, source, entry.size) { report(t(R.string.msg_downloading_temporary_copy), it) }
                        storage.install(snapshot, entry, source, report)
                    }
                }
                record(storage.scan(snapshot.tree), false)
            }
            completionNote = if (cleanupWarning) t(R.string.msg_books_added_and_verified_phone_cleanup_needed) else p(R.plurals.msg_1_d_files_added_read_back_verified, current.additions.size, current.additions.size)
            status = completionNote!!
        } finally { rescanAfterWrite(selected.tree) }
    }

    fun install(entry: CatalogEntry, expectedProfileId: String? = selectedProfileId) = work("Install", entry, target = viewedProfile?.name) {
        val selected = destination(expectedProfileId)
        val cleaned = AudioTransfers.install(cache(entry), audioRetention) { source ->
            storage.install(selected, entry, source) { stage, bytes -> progress(stage, bytes, entry.size) }
        }
        record(storage.scan(selected.tree))
        status = t(R.string.msg_installed_and_read_back_verified_1_s_unplug_and_tap_the_book_to_chec, entry.title)
        if (!cleaned) completionNote = t(R.string.msg_installed_and_verified_phone_cleanup_failed_use_phone_storage)
        else if (audioRetention == AudioRetention.TRANSFER_ONLY) completionNote = t(R.string.msg_installed_and_verified_phone_download_deleted)
    }

    fun downloadAndInstall(entry: CatalogEntry, expectedProfileId: String? = selectedProfileId) = work("Install", entry, target = viewedProfile?.name) {
        val selected = destination(expectedProfileId)
        val fresh = storage.scan(selected.tree)
        check(fresh.fingerprint == selected.fingerprint) { t(R.string.error_the_connected_pen_changed_scan_again) }
        check(fresh.files.none { it.name.equals(entry.filename, true) }) { t(R.string.error_this_filename_is_already_on_the_pen) }
        check(fresh.free == null || fresh.free >= entry.size + 1_048_576) { t(R.string.error_the_pen_does_not_have_enough_free_space) }
        phoneAudio.temporary.mkdirs()
        val temporary = File(phoneAudio.temporary, entry.cacheKey)
        AudioTransfers.temporary(temporary) { source ->
            Downloads.fetch(entry.url, source, entry.size) { progress(t(R.string.msg_downloading_temporary_copy), it, entry.size) }
            storage.install(fresh, entry, source) { stage, bytes -> progress(stage, bytes, entry.size) }
        }
        record(storage.scan(selected.tree))
        completionNote = t(R.string.msg_installed_and_verified_temporary_phone_copy_deleted)
        status = t(R.string.msg_installed_and_verified_1_s_temporary_phone_copy_deleted, entry.title)
    }

    fun updateAudioRetention(value: AudioRetention) {
        if (busy) return
        if (demoMode) { status = t(R.string.ui_demo_readonly); failure = true; changed?.invoke(); return }
        audioRetention = value
        changed?.invoke()
    }

    fun deletePhoneDownload(file: File) = work("Deleting phone download") {
        phoneAudio.deleteDownload(file)
        status = t(R.string.msg_phone_download_deleted)
    }

    fun deletePhoneBackups(filename: String) = work("Deleting phone backups") {
        phoneAudio.deleteBackups(filename)
        status = t(R.string.msg_phone_backups_deleted)
    }

    fun clearPhoneAudio(includeBackups: Boolean) = work("Clearing phone audio") {
        if (includeBackups) phoneAudio.clearAll() else { phoneAudio.clearDownloads(); phoneAudio.clearTemporary() }
        status = if (includeBackups) t(R.string.msg_all_phone_audio_deleted) else t(R.string.msg_phone_downloads_deleted_pen_backups_kept)
    }

    fun remove(file: PenFile, expectedProfileId: String? = selectedProfileId) = work("Remove", filename = file.name,
        localTitle = book(file.name, file.id)?.title ?: file.name, target = viewedProfile?.name) {
        val selected = destination(expectedProfileId)
        try {
            storage.remove(selected, file, backups.directory) { stage, bytes -> progress(stage, bytes, file.size) }
            status = t(R.string.msg_removed_from_pen_a_verified_backup_is_saved_on_this_phone)
        } finally {
            backups.load()
            rescanAfterWrite(selected.tree)
        }
    }

    fun restore(backup: RemovalBackup, expectedProfileId: String? = selectedProfileId) = work("Restore", filename = backup.filename,
        localTitle = book(backup.filename, backup.audioId)?.title ?: backup.filename, target = viewedProfile?.name) {
        val selected = destination(expectedProfileId)
        try {
            storage.restore(selected, backup) { stage, bytes -> progress(stage, bytes, backup.size) }
            status = t(R.string.msg_restored_and_read_back_verified_phone_backup_retained)
        } finally { rescanAfterWrite(selected.tree) }
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
                     localTitle: String? = book?.let(::title), target: String? = null, action: () -> Unit) {
        if (busy) return
        if (demoMode) { status = t(R.string.ui_demo_readonly); failure = true; changed?.invoke(); return }
        busy = true; status = taskText(label); failure = false; completionNote = null
        if (filename != null) transfer = TransferInfo(label, book?.cacheKey, filename, localTitle ?: filename, target = target)
        changed?.invoke()
        executor.execute {
            var error: String? = null
            try { action() } catch (e: Exception) { error = e.message ?: t(R.string.msg_operation_failed_reconnect_and_try_again) }
            try { audioUsage = phoneAudio.usage() } catch (e: Exception) { if (error == null) error = e.message }
            main.post {
                if (error != null) { status = error; failure = true }
                if (filename != null) transfer = transfer?.copy(running = false, failed = error != null,
                    stage = error ?: completionNote ?: when (label) {
                        "Download" -> t(R.string.msg_saved_offline)
                        "Remove" -> t(R.string.msg_removed_from_pen_phone_backup_kept)
                        else -> t(R.string.msg_read_back_verified)
                    }, percent = null)
                busy = false; changed?.invoke()
            }
        }
    }
}
