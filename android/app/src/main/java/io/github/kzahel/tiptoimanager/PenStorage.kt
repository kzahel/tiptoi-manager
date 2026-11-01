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
        val cursor = resolver.query(children, columns, null, null, null) ?: error("Could not read this folder.")
        cursor.use {
            while (it.moveToNext()) result.add(Document(
                Documents.buildDocumentUriUsingTree(tree, it.getString(0)), it.getString(1), it.getLong(2), it.getInt(3),
                if (it.isNull(4)) null else it.getLong(4).takeIf { date -> date > 0 }))
        }
        return result
    }

    private fun metadata(entries: List<Document>): ByteArray {
        val metadata = entries.find { it.name == ".tiptoi.log" }
            ?: error("Choose the pen's top-level folder. Its .tiptoi.log identity file is missing.")
        val bytes = resolver.openInputStream(metadata.uri)?.use { it.readBytesBounded(4096) }
            ?: error("Could not read the pen identity.")
        require(bytes.size == 64) { "This pen's identity format is not supported by the spike." }
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
                    ?: error("File cannot be opened.")
                id = Gme.productId(header)
            } catch (e: Exception) { problem = e.message ?: "Unreadable header" }
            PenFile(entry.uri, entry.name, entry.size, id, problem, entry.modifiedAt)
        }.sortedBy { it.name.lowercase() }
        val free = runCatching {
            val file = entries.firstOrNull { it.name == ".tiptoi.log" } ?: error("No file descriptor available.")
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
        progress("Checking cached file", 0)
        val source = cached.inputStream().buffered().use { Gme.inspect(it, entry.size, entry.id) }
        copyVerified(snapshot, entry.filename, cached, source.size, source.sha256, progress)
    }

    fun restore(snapshot: PenSnapshot, backup: RemovalBackup, progress: (String, Long) -> Unit) {
        progress("Checking backup", 0)
        val source = backup.file.inputStream().buffered().use { RawIntegrity.inspect(it, backup.size) }
        check(source.sha256 == backup.sha256) { "The phone backup changed. It was not restored." }
        copyVerified(snapshot, backup.filename, backup.file, backup.size, backup.sha256, progress)
    }

    /** A verified, durably saved phone copy is required before the delete call. */
    fun remove(snapshot: PenSnapshot, selected: PenFile, backupRoot: File,
               progress: (String, Long) -> Unit): RemovalBackup {
        check(BookActions.removable(selected.name, selected.id)) { "Shared support files cannot be removed here." }
        check(snapshot.files.any { sameFile(it, selected) }) { "Select a file from the scanned pen first." }
        val fresh = scan(snapshot.tree)
        check(fresh.fingerprint == snapshot.fingerprint) { "The connected pen changed. Scan again." }
        val target = fresh.files.singleOrNull { sameFile(it, selected) }
            ?: error("This file changed since the scan. Scan again before removing it.")
        backupRoot.mkdirs()
        check(target.size <= Long.MAX_VALUE - 1_048_576 &&
            android.os.StatFs(backupRoot.path).availableBytes >= target.size + 1_048_576) {
            "The phone needs more space for a backup. Nothing was removed from the pen."
        }
        val folder = File(backupRoot, UUID.randomUUID().toString()).apply { check(mkdir()) { "Could not create a backup folder." } }
        var verified = false
        try {
            val partial = File(folder, "content.part")
            progress("Saving phone backup", 0)
            val copied = partial.outputStream().use { output ->
                val result = resolver.openInputStream(target.uri)?.buffered()?.use {
                    RawIntegrity.copy(it, output, target.size) { bytes -> progress("Saving phone backup", bytes) }
                } ?: error("Could not read the selected file. Nothing was removed.")
                output.fd.sync()
                result
            }
            progress("Checking phone backup", 0)
            val checked = partial.inputStream().buffered().use { RawIntegrity.inspect(it, target.size) }
            check(checked.sha256 == copied.sha256) { "Backup verification failed. Nothing was removed." }
            val savedFile = File(folder, "content.gme")
            check(partial.renameTo(savedFile)) { "Could not finalize the backup. Nothing was removed." }
            val backup = RemovalBackup(folder, target.name, target.size, target.id, target.modifiedAt,
                snapshot.fingerprint, checked.sha256, System.currentTimeMillis(), snapshot.identity.key)
            backup.saveManifest()
            syncDirectory(folder); syncDirectory(backupRoot)
            verified = true // From here, retain the backup even when delete or confirmation fails.
            progress("Rechecking pen file", 0)
            val current = scan(snapshot.tree)
            check(current.fingerprint == snapshot.fingerprint && current.files.any { sameFile(it, target) }) {
                "The pen or file changed. The backup is safe; the file was not removed."
            }
            val original = resolver.openInputStream(target.uri)?.buffered()?.use {
                RawIntegrity.inspect(it, target.size) { bytes -> progress("Rechecking pen file", bytes) }
            } ?: error("Could not recheck the file. The backup is safe.")
            check(original.sha256 == checked.sha256) { "The pen file changed during backup. It was not removed." }
            check(fingerprint(documents(snapshot.tree)) == snapshot.fingerprint) { "The pen changed. The backup is safe." }
            progress("Removing from pen", target.size)
            check(Documents.deleteDocument(resolver, target.uri)) { "Removal was not confirmed. The phone backup is safe; scan again." }
            val after = scan(snapshot.tree)
            check(after.fingerprint == snapshot.fingerprint && after.files.none { it.name.equals(target.name, true) }) {
                "Removal could not be confirmed. The phone backup is safe; scan again."
            }
            check(after.files.map { Triple(it.name, it.size, it.id) }.toSet() ==
                current.files.filterNot { it.uri == target.uri }.map { Triple(it.name, it.size, it.id) }.toSet()) {
                "Other inventory changes were observed. The phone backup is safe; scan again."
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
        check(fresh.fingerprint == snapshot.fingerprint) { "The connected pen changed. Scan it again." }
        check(fresh.files.none { it.name.equals(filename, true) }) { "This filename is already on the pen. Existing files are preserved." }
        check(fresh.free == null || fresh.free >= size + 1_048_576) { "The pen does not have enough free space." }
        val stageName = ".tiptoi-manager-${UUID.randomUUID()}.part"
        var staging: Uri? = null
        try {
            staging = Documents.createDocument(resolver, root(snapshot.tree), "application/octet-stream", stageName)
                ?: error("The storage provider could not create a file.")
            check(documentName(staging) == stageName) { "The provider changed the temporary filename." }
            val flags = resolver.query(staging, arrayOf(Documents.Document.COLUMN_FLAGS), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
            check(flags and Documents.Document.FLAG_SUPPORTS_RENAME != 0) { "This storage provider cannot safely finalize an install." }
            val target = staging
            val descriptor = resolver.openFileDescriptor(target, "wt") ?: error("Could not open the temporary file.")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                cached.inputStream().buffered().use { input ->
                    RawIntegrity.copy(input, output, size) { bytes -> progress("Copying to pen", bytes) }
                }
                output.flush(); descriptor.fileDescriptor.sync()
            }
            progress("Reading back from pen", 0)
            val checked = resolver.openInputStream(target)?.buffered()?.use {
                RawIntegrity.inspect(it, size) { bytes -> progress("Reading back from pen", bytes) }
            } ?: error("Could not verify the transferred file.")
            check(checked.sha256 == sha256) { "Read-back hash differs from the phone copy." }
            check(fingerprint(documents(snapshot.tree)) == snapshot.fingerprint) { "The pen changed during installation." }
            check(documents(snapshot.tree).none { it.name.equals(filename, true) }) { "The destination appeared during transfer; preserving it." }
            val final = Documents.renameDocument(resolver, target, filename)
                ?: error("Could not finalize the verified file.")
            staging = null
            check(documentName(final) == filename) { "The provider changed the final filename; inspect the pen." }
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
        require(output.size() + n <= limit) { "Unexpectedly large pen metadata." }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
