package io.github.kzahel.tiptoimanager

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PenProfilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun metadata(serial: String, firmware: String = "7GE0026", language: String = "GERMAN") = ByteArray(64) { 255.toByte() }.apply {
        listOf(serial, firmware, language, "CPU").forEachIndexed { index, field -> field.toByteArray().copyInto(this, index * 16) }
    }
    private fun entry(name: String, id: Int = 77, size: Long = 30) = CatalogEntry(id, "1", "https://cdn.ravensburger.de/db/applications/$name", name, size)
    private fun store(): PenProfiles = PenProfiles(File(temporary.newFolder(), "pens.json")).apply { load() }
    private fun observe(store: PenProfiles, serial: String, files: List<SavedPenFile> = emptyList(), firmware: String = "7GE0026") =
        metadata(serial, firmware).let { store.observe(PenIdentity.parse(it), sha256(it), "content://fixture/tree/same-volume", files, 1_000_000, 123) }

    @Test fun serialIdentityIgnoresFirmwareLanguageAndPaddingButSessionHashDoesNot() {
        val original = metadata("AB123456")
        val updated = metadata("AB123456", "7GE0027", "FRENCH")
        assertEquals(PenIdentity.parse(original).key, PenIdentity.parse(updated).key)
        assertNotEquals(sha256(original), sha256(updated))
        assertNotEquals(PenIdentity.parse(original).key, PenIdentity.parse(metadata("CD654321")).key)
        assertEquals("3456", PenIdentity.parse(original).serialSuffix)
        assertEquals("7GE0027", PenIdentity.parse(updated).firmware)
        val nullPadded = original.copyOf().apply { fill(0, 8, 16) }
        assertEquals(PenIdentity.parse(original).key, PenIdentity.parse(nullPadded).key)
        assertFalse(PenIdentity.parse(ByteArray(64)).hasSerial)
        assertThrows(IllegalArgumentException::class.java) { PenIdentity.parse(ByteArray(63)) }
    }

    @Test fun namesAndSeparateInventoriesSurviveReconnectFirmwareUpdatesAndReload() {
        val root = temporary.newFolder(); val file = File(root, "pens.json")
        val store = PenProfiles(file).apply { load() }
        val one = observe(store, "AB123456", listOf(SavedPenFile("One.gme", 30, 77, null, 100)))
        val two = observe(store, "CD654321", listOf(SavedPenFile("Two.gme", 40, 78, null, null)))
        store.rename(one.id, "Bedroom"); store.rename(two.id, "Travel")
        val update = observe(store, "AB123456", one.files, "7GE0027")
        assertEquals(one.id, update.id); assertEquals("Bedroom", update.name)
        val reloaded = PenProfiles(file).apply { load() }
        assertEquals(2, reloaded.all.size)
        assertEquals(listOf("One.gme"), reloaded.all.single { it.id == one.id }.files.map { it.name })
        assertEquals(listOf("Two.gme"), reloaded.all.single { it.id == two.id }.files.map { it.name })
        assertTrue(reloaded.all.single { it.id == one.id }.fingerprints.contains(one.fingerprint))
        assertFalse(file.readText().contains("AB123456"))
    }

    @Test fun selectionsAreSharedOrIndividualAndNeverDeduplicateByAudioId() {
        val file = File(temporary.newFolder(), "pens.json")
        val store = PenProfiles(file).apply { load() }; val one = observe(store, "AB123456"); val two = observe(store, "CD654321")
        val a = entry("A.gme"); val b = entry("B.gme")
        store.setTargets(a, setOf(one.id), false)
        store.setTargets(b, emptySet(), true)
        assertEquals(setOf("A.gme", "B.gme"), store.targets(store.all.single { it.id == one.id }).map { it.filename }.toSet())
        assertEquals(listOf("B.gme"), store.targets(store.all.single { it.id == two.id }).map { it.filename })
        store.useFamily(two.id, false)
        assertTrue(store.targets(store.all.single { it.id == two.id }).isEmpty())
        val loaded = PenProfiles(file).apply { load() }
        assertEquals(listOf(b), loaded.family)
        assertEquals(listOf(a), loaded.all.single { it.id == one.id }.additions)
        assertFalse(loaded.all.single { it.id == two.id }.useFamily)
        store.forget(two.id)
        assertEquals(1, store.all.size); assertEquals(listOf(b), store.family)
    }

    @Test fun plansOnlyAddMissingFilesPreserveExtrasAndBlockConflicts() {
        val store = store()
        val profile = observe(store, "AB123456", listOf(SavedPenFile("A.gme", 30, 77, null, 100),
            SavedPenFile("Extra.gme", 50, 90, null, 100), SavedPenFile("Bad.gme", 31, 77, null, 100)))
        val plan = PenPlan.create(profile, listOf(entry("a.gme"), entry("New.gme"), entry("Bad.gme")))
        assertEquals(listOf(PlanState.PRESENT, PlanState.ADD, PlanState.CONFLICT), plan.items.map { it.state })
        assertEquals(listOf("New.gme"), plan.additions.map { it.filename })
        assertEquals(30, plan.bytes.toInt()); assertEquals(1, plan.conflicts)
        val versions = PenPlan.create(profile, listOf(entry("New.gme"), entry("New.gme").copy(version = "2")))
        assertEquals(2, versions.conflicts)
    }

    @Test fun stalePlansCannotTargetAnotherPenOrAnUpdatedSession() {
        val store = store(); val one = observe(store, "AB123456"); val two = observe(store, "CD654321")
        val plan = PenPlan.create(one, listOf(entry("A.gme")))
        plan.requireDestination(one.id, one.identity.key, one.fingerprint)
        assertThrows(IllegalStateException::class.java) { plan.requireDestination(two.id, two.identity.key, two.fingerprint) }
        assertThrows(IllegalStateException::class.java) { plan.requireDestination(one.id, two.identity.key, one.fingerprint) }
        assertThrows(IllegalStateException::class.java) { plan.requireDestination(one.id, one.identity.key, "new-session") }
    }

    @Test fun corruptProfilesAreNotSilentlyOverwrittenAndNamesAreValidated() {
        val file = File(temporary.newFolder(), "pens.json").apply { writeText("broken") }
        val store = PenProfiles(file)
        assertThrows(Exception::class.java) { store.load() }
        assertThrows(IllegalStateException::class.java) { observe(store, "AB123456") }
        assertEquals("broken", file.readText())
        assertThrows(IllegalArgumentException::class.java) { PenProfiles.validName(" ") }
        assertThrows(IllegalArgumentException::class.java) { PenProfiles.validName("a\nb") }
        assertEquals("Bedroom", PenProfiles.validName(" Bedroom "))
    }
}
