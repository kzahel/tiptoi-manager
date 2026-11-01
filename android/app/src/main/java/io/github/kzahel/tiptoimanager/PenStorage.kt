package io.github.kzahel.tiptoimanager

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Documents
import android.system.Os
import java.io.DataInputStream
import java.io.File
import java.util.UUID

data class PenFile(val uri: Uri, val name: String, val size: Long, val id: Int?, val problem: String?, val modifiedAt: Long? = null)
data class PenSnapshot(val tree: Uri, val fingerprint: String, val files: List<PenFile>, val free: Long?, val identity: PenIdentity) {
    val ids: Int get() = files.mapNotNull { it.id }.distinct().size
    val bytes: Long get() = files.sumOf { it.size }
}
private data class Document(val uri: Uri, val name: String, val size: Long, val flags: Int, val modifiedAt: Long?)

class PenStorage(private val resolver: ContentResolver) {
    private fun root(tree: Uri): Uri = Documents.buildDocumentUriUsingTree(tree, Documents.getTreeDocumentId(tree))

    private fun documents(tree: Uri): List<Document> {
        val children = Documents.buildChildDocumentsUriUsingTree(tree, Documents.getTreeDocumentId(tree))
        val columns = arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
            Documents.Document.COLUMN_SIZE, Documents.Document.COLUMN_FLAGS, Documents.Document.COLUMN_LAST_MODIFIED)
        val result = mutableListOf<Document>()
        val cursor = resolver.query(children, columns, null, null, null) ?: error(t(R.string.error_could_not_read_this_folder))
        cursor.use {
            while (it.moveToNext()) result.add(Document(
                Documents.buildDocumentUriUsingTree(tree, it.getString(0)), it.getString(1), it.getLong(2), it.getInt(3),
                if (it.isNull(4)) null else it.getLong(4).takeIf { date -> date > 0 }))
        }
        return result
    }

    private fun metadata(entries: List<Document>): ByteArray {
        val metadata = entries.find { it.name == ".tiptoi.log" }
            ?: error(t(R.string.error_choose_the_pen_s_top_level_folder_its_tiptoi_log_identity_file_is_mi))
        val bytes = resolver.openInputStream(metadata.uri)?.use { it.readBytesBounded(4096) }
            ?: error(t(R.string.error_could_not_read_the_pen_identity))
        require(bytes.size == 64) { t(R.string.error_this_pen_s_identity_format_is_not_supported_by_the_spike) }
        return bytes
    }

    private fun fingerprint(entries: List<Document>) = sha256(metadata(entries))
    fun identify(tree: Uri): Pair<String, PenIdentity> = metadata(documents(tree)).let { sha256(it) to PenIdentity.parse(it) }

    fun scan(tree: Uri): PenSnapshot {
        val entries = documents(tree)
        val raw = metadata(entries)
        val identity = PenIdentity.parse(raw)
        val files = entries.filter { it.name.endsWith(".gme", true) }.map { entry ->
            var id: Int? = null
            var problem: String? = null
            try {
                val header = ByteArray(24)
                resolver.openInputStream(entry.uri)?.use { DataInputStream(it).readFully(header) }
                    ?: error(t(R.string.error_file_cannot_be_opened))
                id = Gme.productId(header)
            } catch (e: Exception) { problem = "unreadable_header" }
            PenFile(entry.uri, entry.name, entry.size, id, problem, entry.modifiedAt)
        }.sortedBy { it.name.lowercase() }
        val free = runCatching {
            val file = entries.firstOrNull { it.name == ".tiptoi.log" } ?: error(t(R.string.error_no_file_descriptor_available))
            resolver.openFileDescriptor(file.uri, "r")!!.use {
                val stat = Os.fstatvfs(it.fileDescriptor)
                (stat.f_bavail * stat.f_frsize).takeIf { bytes -> bytes >= 0 }
            }
        }.getOrNull() ?: runCatching {
            val columns = arrayOf(Documents.Root.COLUMN_DOCUMENT_ID, Documents.Root.COLUMN_AVAILABLE_BYTES)
            var available: Long? = null
            resolver.query(Documents.buildRootsUri(tree.authority!!), columns, null, null, null)?.use {
                while (it.moveToNext()) if (it.getString(0) == Documents.getTreeDocumentId(tree) && !it.isNull(1)) {
                    available = it.getLong(1).takeIf { value -> value >= 0 }
                }
            }
            available
        }.getOrNull()
        return PenSnapshot(tree, sha256(raw), files, free, identity)
    }

    fun install(snapshot: PenSnapshot, entry: CatalogEntry, cached: File, progress: (String, Long) -> Unit) {
        progress(t(R.string.msg_checking_cached_file), 0)
        val source = cached.inputStream().buffered().use { Gme.inspect(it, entry.size, entry.id) }
        copyVerified(snapshot, entry.filename, cached, source.size, source.sha256, progress)
    }

    fun restore(snapshot: PenSnapshot, backup: RemovalBackup, progress: (String, Long) -> Unit) {
        progress(t(R.string.msg_checking_backup), 0)
        val source = backup.file.inputStream().buffered().use { RawIntegrity.inspect(it, backup.size) }
        check(source.sha256 == backup.sha256) { t(R.string.error_the_phone_backup_changed_it_was_not_restored) }
        copyVerified(snapshot, backup.filename, backup.file, backup.size, backup.sha256, progress)
    }

    /** A verified, durably saved phone copy is required before the delete call. */
    fun remove(snapshot: PenSnapshot, selected: PenFile, backupRoot: File,
               progress: (String, Long) -> Unit): RemovalBackup {
        check(BookActions.removable(selected.name, selected.id)) { t(R.string.error_shared_support_files_cannot_be_removed_here) }
        check(snapshot.files.any { sameFile(it, selected) }) { t(R.string.error_select_a_file_from_the_scanned_pen_first) }
        val fresh = scan(snapshot.tree)
        check(fresh.fingerprint == snapshot.fingerprint) { t(R.string.error_the_connected_pen_changed_scan_again) }
        val target = fresh.files.singleOrNull { sameFile(it, selected) }
            ?: error(t(R.string.error_this_file_changed_since_the_scan_scan_again_before_removing_it))
        backupRoot.mkdirs()
        check(target.size <= Long.MAX_VALUE - 1_048_576 &&
            android.os.StatFs(backupRoot.path).availableBytes >= target.size + 1_048_576) {
            t(R.string.msg_the_phone_needs_more_space_for_a_backup_nothing_was_removed_from_the)
        }
        val folder = File(backupRoot, UUID.randomUUID().toString()).apply { check(mkdir()) { t(R.string.error_could_not_create_a_backup_folder) } }
        var verified = false
        try {
            val partial = File(folder, "content.part")
            progress(t(R.string.msg_saving_phone_backup), 0)
            val copied = partial.outputStream().use { output ->
                val result = resolver.openInputStream(target.uri)?.buffered()?.use {
                    RawIntegrity.copy(it, output, target.size) { bytes -> progress(t(R.string.msg_saving_phone_backup), bytes) }
                } ?: error(t(R.string.error_could_not_read_the_selected_file_nothing_was_removed))
                output.fd.sync()
                result
            }
            progress(t(R.string.msg_checking_phone_backup), 0)
            val checked = partial.inputStream().buffered().use { RawIntegrity.inspect(it, target.size) }
            check(checked.sha256 == copied.sha256) { t(R.string.error_backup_verification_failed_nothing_was_removed) }
            val savedFile = File(folder, "content.gme")
            check(partial.renameTo(savedFile)) { t(R.string.error_could_not_finalize_the_backup_nothing_was_removed) }
            val backup = RemovalBackup(folder, target.name, target.size, target.id, target.modifiedAt,
                snapshot.fingerprint, checked.sha256, System.currentTimeMillis(), snapshot.identity.key)
            backup.saveManifest()
            syncDirectory(folder); syncDirectory(backupRoot)
            verified = true // From here, retain the backup even when delete or confirmation fails.
            progress(t(R.string.msg_rechecking_pen_file), 0)
            val current = scan(snapshot.tree)
            check(current.fingerprint == snapshot.fingerprint && current.files.any { sameFile(it, target) }) {
                t(R.string.msg_the_pen_or_file_changed_the_backup_is_safe_the_file_was_not_removed)
            }
            val original = resolver.openInputStream(target.uri)?.buffered()?.use {
                RawIntegrity.inspect(it, target.size) { bytes -> progress(t(R.string.msg_rechecking_pen_file), bytes) }
            } ?: error(t(R.string.error_could_not_recheck_the_file_the_backup_is_safe))
            check(original.sha256 == checked.sha256) { t(R.string.error_the_pen_file_changed_during_backup_it_was_not_removed) }
            check(fingerprint(documents(snapshot.tree)) == snapshot.fingerprint) { t(R.string.error_the_pen_changed_the_backup_is_safe) }
            progress(t(R.string.msg_removing_from_pen), target.size)
            check(Documents.deleteDocument(resolver, target.uri)) { t(R.string.error_removal_was_not_confirmed_the_phone_backup_is_safe_scan_again) }
            val after = scan(snapshot.tree)
            check(after.fingerprint == snapshot.fingerprint && after.files.none { it.name.equals(target.name, true) }) {
                t(R.string.msg_removal_could_not_be_confirmed_the_phone_backup_is_safe_scan_again)
            }
            check(after.files.map { Triple(it.name, it.size, it.id) }.toSet() ==
                current.files.filterNot { it.uri == target.uri }.map { Triple(it.name, it.size, it.id) }.toSet()) {
                t(R.string.msg_other_inventory_changes_were_observed_the_phone_backup_is_safe_scan)
            }
            return backup
        } finally {
            if (!verified) folder.deleteRecursively()
        }
    }

    private fun sameFile(a: PenFile, b: PenFile): Boolean = a.uri == b.uri && a.name == b.name &&
        a.size == b.size && a.id == b.id && a.modifiedAt == b.modifiedAt

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun copyVerified(snapshot: PenSnapshot, filename: String, cached: File, size: Long,
                             sha256: String, progress: (String, Long) -> Unit) {
        require(Catalog.safeFilename(filename))
        val fresh = scan(snapshot.tree)
        check(fresh.fingerprint == snapshot.fingerprint) { t(R.string.error_the_connected_pen_changed_scan_it_again) }
        check(fresh.files.none { it.name.equals(filename, true) }) { t(R.string.error_this_filename_is_already_on_the_pen_existing_files_are_preserved) }
        check(fresh.free == null || fresh.free >= size + 1_048_576) { t(R.string.error_the_pen_does_not_have_enough_free_space) }
        val stageName = ".tiptoi-manager-${UUID.randomUUID()}.part"
        var staging: Uri? = null
        try {
            staging = Documents.createDocument(resolver, root(snapshot.tree), "application/octet-stream", stageName)
                ?: error(t(R.string.error_the_storage_provider_could_not_create_a_file))
            check(documentName(staging) == stageName) { t(R.string.error_the_provider_changed_the_temporary_filename) }
            val flags = resolver.query(staging, arrayOf(Documents.Document.COLUMN_FLAGS), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
            check(flags and Documents.Document.FLAG_SUPPORTS_RENAME != 0) { t(R.string.error_this_storage_provider_cannot_safely_finalize_an_install) }
            val target = staging
            val descriptor = resolver.openFileDescriptor(target, "wt") ?: error(t(R.string.error_could_not_open_the_temporary_file))
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                cached.inputStream().buffered().use { input ->
                    RawIntegrity.copy(input, output, size) { bytes -> progress(t(R.string.msg_copying_to_pen), bytes) }
                }
                output.flush(); descriptor.fileDescriptor.sync()
            }
            progress(t(R.string.msg_reading_back_from_pen), 0)
            val checked = resolver.openInputStream(target)?.buffered()?.use {
                RawIntegrity.inspect(it, size) { bytes -> progress(t(R.string.msg_reading_back_from_pen), bytes) }
            } ?: error(t(R.string.error_could_not_verify_the_transferred_file))
            check(checked.sha256 == sha256) { t(R.string.error_read_back_hash_differs_from_the_phone_copy) }
            check(fingerprint(documents(snapshot.tree)) == snapshot.fingerprint) { t(R.string.error_the_pen_changed_during_installation) }
            check(documents(snapshot.tree).none { it.name.equals(filename, true) }) { t(R.string.error_the_destination_appeared_during_transfer_preserving_it) }
            val final = Documents.renameDocument(resolver, target, filename)
                ?: error(t(R.string.error_could_not_finalize_the_verified_file))
            staging = null
            check(documentName(final) == filename) { t(R.string.error_the_provider_changed_the_final_filename_inspect_the_pen) }
        } finally {
            staging?.let { runCatching { Documents.deleteDocument(resolver, it) } }
        }
    }

    private fun documentName(uri: Uri): String? = resolver.query(uri,
        arrayOf(Documents.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(512)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(output.size() + n <= limit) { t(R.string.error_unexpectedly_large_pen_metadata) }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
