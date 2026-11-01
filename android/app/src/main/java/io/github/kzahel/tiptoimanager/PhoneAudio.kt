package io.github.kzahel.tiptoimanager

import java.io.File

enum class AudioRetention(private val labelId: Int) {
    KEEP(R.string.label_keep_downloads), TRANSFER_ONLY(R.string.label_transfer_only);
    val label: String get() = t(labelId)
}

data class AudioUsage(val downloads: Long, val backups: Long, val temporary: Long) {
    val total: Long get() = downloads + backups + temporary
}

/** Only app-owned audio roots are reachable. Catalog, artwork, settings and pen storage are separate. */
class PhoneAudio(val downloads: File, val temporary: File, private val backups: RemovalBackups) {
    fun downloadFiles(): List<File> = children(downloads).filter { it.isFile }.sortedBy { it.name }
    fun usage() = AudioUsage(bytes(downloads), bytes(backups.directory), bytes(temporary))
    fun deleteDownload(file: File) {
        require(file.parentFile?.canonicalFile == downloads.canonicalFile && file.canonicalFile.parentFile == downloads.canonicalFile)
        check(!file.exists() || file.delete()) { t(R.string.error_could_not_delete_the_phone_download) }
    }
    fun deleteBackups(filename: String) {
        try {
            backups.all.filter { it.filename.equals(filename, true) }.forEach { backup ->
                erase(backups.directory, backup.directory)
            }
        } finally { backups.load() }
    }
    fun clearDownloads() = clear(downloads)
    fun clearTemporary() = clear(temporary)
    fun clearAll() {
        try { clear(downloads); clear(temporary); clear(backups.directory) }
        finally { backups.load() }
    }
    private fun children(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles()?.toList() ?: error(t(R.string.error_could_not_list_phone_audio_files))
    }
    private fun bytes(directory: File): Long = children(directory).sumOf { child ->
        require(child.canonicalFile.parentFile == directory.canonicalFile) { t(R.string.error_unexpected_link_in_phone_audio_storage) }
        if (child.isDirectory) bytes(child) else child.length()
    }
    private fun clear(directory: File) {
        children(directory).forEach { erase(directory, it) }
    }
    private fun erase(parent: File, child: File) {
        require(child.canonicalFile.parentFile == parent.canonicalFile) { t(R.string.error_unexpected_link_in_phone_audio_storage) }
        if (child.isDirectory) clear(child)
        check(!child.exists() || child.delete()) { t(R.string.error_some_phone_audio_could_not_be_deleted_try_again) }
    }
}

/** Temporary transfer files are removed on failure too. Existing offline downloads survive failed installs. */
object AudioTransfers {
    fun temporary(source: File, operation: (File) -> Unit) {
        try { operation(source) } finally {
            check(!source.exists() || source.delete()) { t(R.string.error_transfer_ended_but_the_temporary_phone_file_could_not_be_deleted_cle) }
        }
    }
    fun install(source: File, retention: AudioRetention, operation: (File) -> Unit): Boolean {
        operation(source) // The caller must complete read-back verification before returning.
        return retention != AudioRetention.TRANSFER_ONLY || !source.exists() || source.delete()
    }
}
