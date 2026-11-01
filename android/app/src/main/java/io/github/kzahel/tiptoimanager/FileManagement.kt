package io.github.kzahel.tiptoimanager

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** These are filesystem dates, never playback or inferred installation dates. */
enum class PenSort(val label: String) {
    TITLE("Title"), OLDEST("File date · oldest first"), NEWEST("File date · newest first"), LARGEST("Size · largest first");
    fun <T> order(items: List<T>, title: (T) -> String, size: (T) -> Long, date: (T) -> Long?): List<T> {
        val names = compareBy<T> { title(it).lowercase(Locale.ROOT) }
        val comparator = when (this) {
            TITLE -> names
            OLDEST -> compareBy<T> { date(it) == null }.thenBy { date(it) ?: Long.MAX_VALUE }.then(names)
            NEWEST -> compareBy<T> { date(it) == null }.thenByDescending { date(it) ?: Long.MIN_VALUE }.then(names)
            LARGEST -> compareByDescending<T> { size(it) }.then(names)
        }
        return items.sortedWith(comparator)
    }
}

enum class BookPrimaryAction { NONE, REMOVE, RESTORE, INSTALL, DOWNLOAD, DOWNLOAD_INSTALL, CONNECT, MAKE_SPACE }
object BookActions {
    fun primary(onPen: Boolean, removable: Boolean, backup: Boolean, downloaded: Boolean,
                connected: Boolean, inCatalog: Boolean, free: Long?, bytes: Long, transferOnly: Boolean = false): BookPrimaryAction = when {
        onPen -> if (removable) BookPrimaryAction.REMOVE else BookPrimaryAction.NONE
        (backup || downloaded) && !connected -> BookPrimaryAction.CONNECT
        (backup || downloaded) && free != null && free < bytes + 1_048_576 -> BookPrimaryAction.MAKE_SPACE
        backup -> BookPrimaryAction.RESTORE
        downloaded -> BookPrimaryAction.INSTALL
        inCatalog && transferOnly && !connected -> BookPrimaryAction.CONNECT
        inCatalog && transferOnly && free != null && free < bytes + 1_048_576 -> BookPrimaryAction.MAKE_SPACE
        inCatalog && transferOnly -> BookPrimaryAction.DOWNLOAD_INSTALL
        inCatalog -> BookPrimaryAction.DOWNLOAD
        else -> BookPrimaryAction.NONE
    }
    fun removable(filename: String, id: Int?): Boolean = Catalog.safeFilename(filename) &&
        id != 14999 && !filename.equals("WissenManage.gme", true)
}

data class FileDigest(val bytes: Long, val sha256: String)
object RawIntegrity {
    fun inspect(input: InputStream, expectedSize: Long, progress: (Long) -> Unit = {}): FileDigest =
        copy(input, null, expectedSize, progress)
    fun copy(input: InputStream, output: OutputStream?, expectedSize: Long, progress: (Long) -> Unit = {}): FileDigest {
        require(expectedSize >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= expectedSize) { "The file grew or its size is unavailable. Rescan before trying again." }
            output?.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            progress(total)
        }
        check(total == expectedSize) { "The file changed or the copy ended early. Nothing was intentionally removed." }
        return FileDigest(total, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}

data class RemovalBackup(val directory: File, val filename: String, val size: Long, val audioId: Int?,
    val sourceDate: Long?, val fingerprint: String, val sha256: String, val savedAt: Long, val identityKey: String? = null) {
    val file: File get() = File(directory, "content.gme")
    fun saveManifest() {
        val json = JSONObject().put("filename", filename).put("size", size).put("audioId", audioId)
            .put("sourceDate", sourceDate).put("fingerprint", fingerprint).put("sha256", sha256).put("savedAt", savedAt).put("identityKey", identityKey)
        atomicWrite(File(directory, "backup.json"), json.toString().toByteArray(Charsets.UTF_8))
    }
}

class RemovalBackups(val directory: File) {
    @Volatile var all: List<RemovalBackup> = emptyList()
        private set
    fun load() {
        directory.mkdirs()
        all = directory.listFiles()?.filter { it.isDirectory }?.mapNotNull { folder ->
            runCatching {
                val json = JSONObject(File(folder, "backup.json").readText())
                val name = json.getString("filename")
                val size = json.getLong("size")
                val sha = json.getString("sha256")
                val fingerprint = json.getString("fingerprint")
                require(Catalog.safeFilename(name) && size >= 0 && sha.matches(Regex("[0-9a-f]{64}")) && fingerprint.matches(Regex("[0-9a-f]{64}")))
                require(File(folder, "content.gme").isFile && File(folder, "content.gme").length() == size)
                RemovalBackup(folder, name, size, if (json.isNull("audioId")) null else json.getInt("audioId"),
                    if (json.isNull("sourceDate")) null else json.getLong("sourceDate"), fingerprint, sha, json.getLong("savedAt"),
                    if (json.isNull("identityKey")) null else json.getString("identityKey"))
            }.getOrNull()
        }?.sortedByDescending { it.savedAt }.orEmpty()
    }
    fun latest(filename: String, fingerprint: String? = null, identityKey: String? = null, fingerprints: Set<String> = emptySet()): RemovalBackup? {
        val matches = all.filter { it.filename.equals(filename, true) }
        return matches.firstOrNull { identityKey != null && it.identityKey == identityKey }
            ?: matches.firstOrNull { it.fingerprint == fingerprint || it.fingerprint in fingerprints } ?: matches.firstOrNull()
    }
    fun latestCopies(): List<RemovalBackup> = all.distinctBy { it.filename.lowercase(Locale.ROOT) }
}
