package io.github.kzahel.tiptoimanager

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class PenIdentity(val key: String, val serialSuffix: String?, val firmware: String?, val language: String?, val processor: String?) {
    val hasSerial: Boolean get() = key.startsWith("serial:")
    companion object {
        fun parse(bytes: ByteArray): PenIdentity {
            require(bytes.size == 64) { "This pen metadata format is not supported." }
            fun field(offset: Int): String? {
                val block = bytes.copyOfRange(offset, offset + 16)
                val end = block.indexOfFirst { it == 0.toByte() || it == 255.toByte() }.let { if (it < 0) 16 else it }
                if (block.take(end).any { (it.toInt() and 255) !in 32..126 } ||
                    block.drop(end).any { it != 0.toByte() && it != 255.toByte() && it != 32.toByte() }) return null
                return String(block, 0, end, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
            }
            val serial = field(0)?.uppercase(Locale.ROOT)?.takeIf {
                it.matches(Regex("[A-Z0-9-]{6,16}")) && it.any(Char::isDigit) && it.any { c -> c != '0' && c != '-' }
            }
            val key = serial?.let { "serial:" + sha256("tiptoi-serial-v1:$it".toByteArray()) }
                ?: "metadata:" + sha256(bytes)
            return PenIdentity(key, serial?.takeLast(4), field(16), field(32), field(48))
        }
    }
}

data class SavedPenFile(val name: String, val size: Long, val id: Int?, val problem: String?, val modifiedAt: Long?)
data class PenProfile(val id: String, val identity: PenIdentity, val name: String, val lastSeen: Long,
    val fingerprint: String, val tree: String, val files: List<SavedPenFile>, val free: Long?,
    val useFamily: Boolean = true, val additions: List<CatalogEntry> = emptyList(), val fingerprints: Set<String> = setOf(fingerprint)) {
    val bytes: Long get() = files.sumOf { it.size }
}

/** Private local records. No URI or saved inventory is sufficient to authorize a write. */
class PenProfiles(private val file: File) {
    @Volatile var all: List<PenProfile> = emptyList()
        private set
    @Volatile var family: List<CatalogEntry> = emptyList()
        private set
    private var loaded = false
    fun load() {
        if (file.exists()) {
            val root = JSONObject(file.readText())
            require(root.getInt("schema") == 1) { "Unsupported pen-profile format." }
            val records = root.getJSONArray("pens").objects().map { json ->
                val identity = json.getJSONObject("identity")
                val key = identity.getString("key")
                require(key.matches(Regex("(serial|metadata):[0-9a-f]{64}")))
                val id = json.getString("id"); UUID.fromString(id)
                val files = json.getJSONArray("files").objects().map {
                    SavedPenFile(it.getString("name"), it.getLong("size"), it.intOrNull("id"), it.stringOrNull("problem"), it.longOrNull("modifiedAt"))
                }
                require(files.all { it.name.endsWith(".gme", true) && it.size >= 0 })
                PenProfile(id, PenIdentity(key, identity.stringOrNull("serialSuffix"), identity.stringOrNull("firmware"),
                    identity.stringOrNull("language"), identity.stringOrNull("processor")), validName(json.getString("name")),
                    json.getLong("lastSeen"), json.getString("fingerprint"), json.getString("tree"), files, json.longOrNull("free"),
                    json.getBoolean("useFamily"), entries(json.getJSONArray("additions")),
                    json.optJSONArray("fingerprints")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: setOf(json.getString("fingerprint")))
            }
            require(records.map { it.id }.distinct().size == records.size && records.map { it.identity.key }.distinct().size == records.size)
            val collection = entries(root.getJSONArray("family"))
            all = records; family = collection
        }
        loaded = true
    }
    fun observe(identity: PenIdentity, fingerprint: String, tree: String, files: List<SavedPenFile>, free: Long?, now: Long): PenProfile {
        val previous = all.find { it.identity.key == identity.key }
        val profile = PenProfile(previous?.id ?: UUID.randomUUID().toString(), identity, previous?.name ?: nextName(), now,
            fingerprint, tree, files, free, previous?.useFamily ?: true, previous?.additions.orEmpty(), previous?.fingerprints.orEmpty() + fingerprint)
        save(all.filterNot { it.id == profile.id } + profile, family)
        return profile
    }
    fun rename(id: String, name: String) = update(id) { it.copy(name = validName(name)) }
    fun forget(id: String) = save(all.filterNot { it.id == id }, family)
    fun useFamily(id: String, enabled: Boolean) = update(id) { it.copy(useFamily = enabled) }
    fun select(entry: CatalogEntry, profileId: String?, enabled: Boolean) {
        fun changed(old: List<CatalogEntry>) = old.filterNot { it.cacheKey == entry.cacheKey && it.filename.equals(entry.filename, true) } +
            if (enabled) listOf(entry) else emptyList()
        if (profileId == null) save(all, changed(family)) else update(profileId) { it.copy(additions = changed(it.additions)) }
    }
    fun setTargets(entry: CatalogEntry, profileIds: Set<String>, includeFamily: Boolean) {
        require(profileIds.all { id -> all.any { it.id == id } })
        fun changed(old: List<CatalogEntry>, enabled: Boolean) = old.filterNot {
            it.filename.equals(entry.filename, true) && (enabled || it.cacheKey == entry.cacheKey)
        } + if (enabled) listOf(entry) else emptyList()
        save(all.map { it.copy(additions = changed(it.additions, it.id in profileIds)) }, changed(family, includeFamily))
    }
    fun targets(profile: PenProfile): List<CatalogEntry> =
        ((if (profile.useFamily) family else emptyList()) + profile.additions).distinctBy { it.filename.lowercase(Locale.ROOT) to it.cacheKey }
    private fun update(id: String, transform: (PenProfile) -> PenProfile) {
        require(all.any { it.id == id }) { "This pen profile no longer exists." }
        save(all.map { if (it.id == id) transform(it) else it }, family)
    }
    private fun nextName(): String {
        var number = 1
        while (all.any { it.name == "Pen $number" }) number++
        return "Pen $number"
    }
    private fun save(pens: List<PenProfile>, selected: List<CatalogEntry>) {
        check(loaded) { "Load pen profiles successfully before changing them." }
        val root = JSONObject().put("schema", 1).put("family", encodeEntries(selected))
        root.put("pens", JSONArray(pens.map { pen ->
            JSONObject().put("id", pen.id).put("identity", JSONObject().put("key", pen.identity.key)
                .put("serialSuffix", pen.identity.serialSuffix).put("firmware", pen.identity.firmware)
                .put("language", pen.identity.language).put("processor", pen.identity.processor))
                .put("name", pen.name).put("lastSeen", pen.lastSeen).put("fingerprint", pen.fingerprint).put("tree", pen.tree)
                .put("free", pen.free).put("useFamily", pen.useFamily).put("additions", encodeEntries(pen.additions)).put("fingerprints", JSONArray(pen.fingerprints.toList()))
                .put("files", JSONArray(pen.files.map { entry -> JSONObject().put("name", entry.name).put("size", entry.size)
                    .put("id", entry.id).put("problem", entry.problem).put("modifiedAt", entry.modifiedAt) }))
        }))
        atomicWrite(file, root.toString().toByteArray())
        all = pens; family = selected
    }
    private fun entries(array: JSONArray): List<CatalogEntry> = array.objects().map {
        CatalogEntry(it.getInt("id"), it.getString("version"), it.getString("url"), it.getString("filename"), it.getLong("size")).also { entry ->
            require(Catalog.safeFilename(entry.filename) && Catalog.allowedUrl(entry.url) && entry.size in 28..2_000_000_000L && entry.id in 1..14999)
        }
    }
    private fun encodeEntries(entries: List<CatalogEntry>) = JSONArray(entries.map {
        JSONObject().put("id", it.id).put("version", it.version).put("url", it.url).put("filename", it.filename).put("size", it.size)
    })
    companion object {
        fun validName(name: String): String = name.trim().also {
            require(it.isNotEmpty() && it.length <= 40 && it.none { c -> c.isISOControl() }) { "Use a name with 1–40 characters." }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
private fun JSONObject.intOrNull(key: String) = if (isNull(key)) null else getInt(key)

enum class PlanState { PRESENT, ADD, CONFLICT }
data class PlanItem(val entry: CatalogEntry, val state: PlanState)
data class PenPlan(val profileId: String, val identityKey: String, val fingerprint: String, val items: List<PlanItem>) {
    val additions: List<CatalogEntry> get() = items.filter { it.state == PlanState.ADD }.map { it.entry }
    val conflicts: Int get() = items.count { it.state == PlanState.CONFLICT }
    val bytes: Long get() = additions.sumOf { it.size }
    fun requireDestination(profileId: String, identityKey: String, fingerprint: String) {
        check(this.profileId == profileId && this.identityKey == identityKey && this.fingerprint == fingerprint) {
            "The destination pen changed. Review this pen's selection again."
        }
    }
    companion object {
        fun create(profile: PenProfile, targets: List<CatalogEntry>): PenPlan {
            val items = targets.map { entry ->
                val existing = profile.files.find { it.name.equals(entry.filename, true) }
                val conflictingSelection = targets.any { it.filename.equals(entry.filename, true) && it.cacheKey != entry.cacheKey }
                PlanItem(entry, when {
                    conflictingSelection -> PlanState.CONFLICT
                    existing == null -> PlanState.ADD
                    existing.id == entry.id && existing.size == entry.size && existing.problem == null -> PlanState.PRESENT
                    else -> PlanState.CONFLICT
                })
            }
            return PenPlan(profile.id, profile.identity.key, profile.fingerprint, items)
        }
    }
}
