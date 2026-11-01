package io.github.kzahel.tiptoimanager

import android.net.Uri

/** Public, read-only sample library. Never persisted or used as a storage destination. */
object DemoLibrary {
    val entries = listOf(
        CatalogEntry(901, "1", "https://example.invalid/forest", "Sample_forest.gme", 84_000_000),
        CatalogEntry(902, "1", "https://example.invalid/space", "Sample_space.gme", 126_000_000),
        CatalogEntry(903, "1", "https://example.invalid/music", "Sample_music.gme", 72_000_000)
    )
    private val names = listOf(R.string.demo_forest, R.string.demo_space, R.string.demo_music)
    fun book(filename: String): BookInfo? = entries.indexOfFirst { it.filename == filename }.takeIf { it >= 0 }?.let { index ->
        BookInfo("sample-$index", t(names[index]), t(R.string.demo_series), t(R.string.demo_description), "", 4, 8,
            null, null, listOf(AudioReference(entries[index].id, filename)))
    }
    fun cover(article: String): Int = when (article) {
        "sample-0" -> R.drawable.cover_forest
        "sample-1" -> R.drawable.cover_space
        else -> R.drawable.cover_music
    }
    val pens: List<PenProfile> get() = listOf(
        profile("00000000-0000-4000-8000-000000000001", t(R.string.demo_home), entries.take(2), 2_900_000_000),
        profile("00000000-0000-4000-8000-000000000002", t(R.string.demo_travel), entries.takeLast(2), 3_100_000_000)
    )
    private fun profile(id: String, name: String, files: List<CatalogEntry>, free: Long) = PenProfile(
        id, PenIdentity("serial:sample-$id", null, null, null, null), name, 1793520000000,
        "sample-$id", "demo://$id", files.mapIndexed { i, entry -> SavedPenFile(entry.filename, entry.size, entry.id, null, 1788307200000 + i * 86400000L) }, free)
    val connected: PenSnapshot get() = pens.first().let { profile -> PenSnapshot(Uri.parse(profile.tree), profile.fingerprint,
        profile.files.map { PenFile(Uri.parse("demo://${it.name}"), it.name, it.size, it.id, null, it.modifiedAt) }, profile.free, profile.identity) }
}
