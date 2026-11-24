package net.fstab.tachiai.provider.abema

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AbemaPreparationTimingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `markers carry only a closed phase, optional bundle slot and elapsed milliseconds`() {
        var now = 1_000L
        val lines = mutableListOf<String>()
        val timing = AbemaPreparationTiming({ now }) { lines += it }
        now = 1_250L; timing.mark(AbemaPreparationPhase.ROUTE_LEASED)
        now = 1_300L; timing.mark(AbemaPreparationPhase.BUNDLE_CACHE_HIT, AbemaPublicBundleAsset.LEGACY_SOURCE)
        now = 1_400L; timing.providerRequest()
        now = 1_500L; timing.providerRequest()
        assertEquals(listOf("ROUTE_LEASED 250", "BUNDLE_CACHE_HIT LEGACY_SOURCE 300", "FIRST_PROVIDER_REQUEST 400"), lines)
        val closed = Regex("[A-Z_]+( [A-Z_]+)? [0-9]+")
        assertTrue(lines.all(closed::matches))
    }

    @Test fun `a failing sink or backwards clock never fails preparation`() {
        var now = 10L
        val timing = AbemaPreparationTiming({ now }) { error("sink unavailable") }
        now = 5L
        timing.mark(AbemaPreparationPhase.PAGE_LOADED)
        val lines = mutableListOf<String>()
        now = 10L
        val backwards = AbemaPreparationTiming({ now }) { lines += it }
        now = 4L; backwards.mark(AbemaPreparationPhase.PAGE_STARTED)
        assertEquals(listOf("PAGE_STARTED 0"), lines)
    }

    @Test fun `bundle cache reports hit or miss, download and verification phases`() {
        val bytes = "synthetic public module registration".toByteArray()
        val identity = AbemaPublicBundleIdentity(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size)
        val directory = File(temporary.root, "bundle")
        val phases = mutableListOf<AbemaPreparationPhase>()
        AbemaPublicBundleCache(directory, identity = identity, observe = { phases += it }).prepare { bytes.copyOf() }
        assertEquals(listOf(AbemaPreparationPhase.BUNDLE_CACHE_MISS, AbemaPreparationPhase.BUNDLE_DOWNLOADED,
            AbemaPreparationPhase.BUNDLE_VERIFIED), phases)
        phases.clear()
        AbemaPublicBundleCache(directory, identity = identity, observe = { phases += it }).prepare { error("no download on a hit") }
        assertEquals(listOf(AbemaPreparationPhase.BUNDLE_CACHE_HIT), phases)
    }
}

class AbemaBundleAdmissionTest {
    @Test fun `per-chunk checks read the saved owner at most once per interval`() {
        var now = 0L
        var ownerReads = 0
        val admission = AbemaBundleAdmission({ now }, live = { true }, owner = { ownerReads++; true }, ownerIntervalMs = 500)
        repeat(200) { assertTrue(admission.current()) }
        assertEquals(1, ownerReads)
        now = 499L; assertTrue(admission.current()); assertEquals(1, ownerReads)
        now = 500L; assertTrue(admission.current()); assertEquals(2, ownerReads)
        // A boundary check always performs a fresh owner read.
        assertTrue(admission.checkOwner()); assertEquals(3, ownerReads)
        // A backwards clock is not trusted to skip the owner read.
        now = 100L; assertTrue(admission.current()); assertEquals(4, ownerReads)
    }

    @Test fun `liveness is checked on every chunk without an owner read`() {
        var live = true
        var ownerReads = 0
        val admission = AbemaBundleAdmission({ 0L }, live = { live }, owner = { ownerReads++; true })
        assertTrue(admission.current())
        live = false
        assertFalse(admission.current())
        assertEquals(1, ownerReads)
        live = true
        assertFalse("cancellation is terminal", admission.current())
        assertFalse(admission.checkOwner())
    }

    @Test fun `owner change or unreadable owner refuses permanently`() {
        var now = 0L
        var owner: () -> Boolean = { true }
        val admission = AbemaBundleAdmission({ now }, live = { true }, owner = { owner() })
        assertTrue(admission.current())
        owner = { error("protected read failed") }
        assertFalse(admission.checkOwner())
        owner = { true }
        now = 10_000L
        assertFalse("a later matching read cannot resume", admission.current())
        val changed = AbemaBundleAdmission({ 0L }, live = { true }, owner = { false })
        assertFalse(changed.current())
    }
}
