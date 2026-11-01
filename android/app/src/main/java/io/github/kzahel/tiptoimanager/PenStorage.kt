package io.github.kzahel.tiptoimanager

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Documents
import android.system.Os
import java.io.DataInputStream
import java.io.File
import java.util.UUID

data class PenFile(val uri: Uri, val name: String, val size: Long, val id: Int?, val problem: String?)
data class PenSnapshot(val tree: Uri, val fingerprint: String, val files: List<PenFile>, val free: Long?) {
    val ids: Int get() = files.mapNotNull { it.id }.distinct().size
    val bytes: Long get() = files.sumOf { it.size }
}
private data class Document(val uri: Uri, val name: String, val size: Long, val flags: Int)

class PenStorage(private val resolver: ContentResolver) {
    private fun root(tree: Uri): Uri = Documents.buildDocumentUriUsingTree(tree, Documents.getTreeDocumentId(tree))

    private fun documents(tree: Uri): List<Document> {
        val children = Documents.buildChildDocumentsUriUsingTree(tree, Documents.getTreeDocumentId(tree))
        val columns = arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
            Documents.Document.COLUMN_SIZE, Documents.Document.COLUMN_FLAGS)
        val result = mutableListOf<Document>()
        val cursor = resolver.query(children, columns, null, null, null) ?: error("Could not read this folder.")
        cursor.use {
            while (it.moveToNext()) result.add(Document(
                Documents.buildDocumentUriUsingTree(tree, it.getString(0)), it.getString(1), it.getLong(2), it.getInt(3)))
        }
        return result
    }

    private fun fingerprint(entries: List<Document>): String {
        val metadata = entries.find { it.name == ".tiptoi.log" }
            ?: error("Choose the pen's top-level folder. Its .tiptoi.log identity file is missing.")
        val bytes = resolver.openInputStream(metadata.uri)?.use { it.readBytesBounded(4096) }
            ?: error("Could not read the pen identity.")
        require(bytes.size == 64) { "This pen's identity format is not supported by the spike." }
        return sha256(bytes)
    }

    fun scan(tree: Uri): PenSnapshot {
        val entries = documents(tree)
        val identity = fingerprint(entries)
        val files = entries.filter { it.name.endsWith(".gme", true) }.map { entry ->
            var id: Int? = null
            var problem: String? = null
            try {
                val header = ByteArray(24)
                resolver.openInputStream(entry.uri)?.use { DataInputStream(it).readFully(header) }
                    ?: error("File cannot be opened.")
                id = Gme.productId(header)
            } catch (e: Exception) { problem = e.message ?: "Unreadable header" }
            PenFile(entry.uri, entry.name, entry.size, id, problem)
        }.sortedBy { it.name.lowercase() }
        val free = runCatching {
            val file = files.firstOrNull() ?: error("No file descriptor available.")
            resolver.openFileDescriptor(file.uri, "r")!!.use {
                val stat = Os.fstatvfs(it.fileDescriptor)
                (stat.f_bavail * stat.f_frsize).takeIf { bytes -> bytes > 0 }
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
        return PenSnapshot(tree, identity, files, free)
    }

    fun install(snapshot: PenSnapshot, entry: CatalogEntry, cached: File, progress: (String, Long) -> Unit) {
        require(Catalog.safeFilename(entry.filename))
        progress("Checking cached file", 0)
        val source = cached.inputStream().buffered().use { Gme.inspect(it, entry.size, entry.id) }
        val fresh = scan(snapshot.tree)
        check(fresh.fingerprint == snapshot.fingerprint) { "The connected pen changed. Scan it again." }
        check(fresh.files.none { it.name.equals(entry.filename, true) }) { "This filename is already on the pen. Existing files are preserved." }
        check(fresh.free == null || fresh.free >= entry.size + 1_048_576) { "The pen does not have enough free space." }
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
            cached.inputStream().buffered().use { input ->
                val descriptor = resolver.openFileDescriptor(target, "wt") ?: error("Could not open the temporary file.")
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n); written += n
                        progress("Copying to pen", written)
                    }
                    output.flush()
                    runCatching { descriptor.fileDescriptor.sync() }
                    check(written == entry.size) { "Transfer is incomplete." }
                }
            }
            progress("Reading back from pen", 0)
            val check = resolver.openInputStream(target)?.buffered()?.use {
                Gme.inspect(it, entry.size, entry.id) { bytes -> progress("Reading back from pen", bytes) }
            } ?: error("Could not verify the transferred file.")
            check(check.sha256 == source.sha256) { "Read-back hash differs from the downloaded file." }
            check(fingerprint(documents(snapshot.tree)) == snapshot.fingerprint) { "The pen changed during installation." }
            check(documents(snapshot.tree).none { it.name.equals(entry.filename, true) }) { "The destination appeared during transfer; preserving it." }
            val final = Documents.renameDocument(resolver, target, entry.filename)
                ?: error("Could not finalize the verified file.")
            staging = null // Never delete a published GME as part of failure recovery.
            check(documentName(final) == entry.filename) { "The provider changed the final filename; inspect the pen." }
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
