package net.fstab.tachiai.platform.media

import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class AcceptedManifestDrmPrewarmTest {
    private class Fixture {
        val calls = mutableListOf<String>()
        var active = true
        var failAcquisition = false
        var failRelease = false
        val format = Format.Builder().build()
        val delegate = object : DrmSessionManager {
            override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) { calls.add("player") }
            override fun prepare() { calls.add("prepare") }
            override fun release() { calls.add("release") }
            override fun acquireSession(dispatcher: DrmSessionEventListener.EventDispatcher?, format: Format): DrmSession? {
                calls.add("acquire"); return null
            }
            override fun preacquireSession(dispatcher: DrmSessionEventListener.EventDispatcher?, format: Format): DrmSessionManager.DrmSessionReference {
                assertSame(this@Fixture.format, format)
                calls.add("preacquire")
                if (failAcquisition) throw IllegalStateException("fixture")
                return DrmSessionManager.DrmSessionReference {
                    calls.add("referenceRelease")
                    if (failRelease) throw IllegalStateException("fixture")
                }
            }
            override fun getCryptoType(format: Format): Int { calls.add("crypto"); return 17 }
        }
        val wrapper = AcceptedManifestDrmPrewarm(delegate, format, null, { active }) { calls.add("requested") }
    }

    @Test fun `prepare alone never prewarms and acceptance needs source preparation`() {
        val f = Fixture()
        f.wrapper.acceptedManifest()
        assertTrue(f.calls.isEmpty())
        f.wrapper.prepare()
        assertEquals(listOf("prepare"), f.calls)
    }
    @Test fun `accepted manifests preacquire once and hold until final release`() {
        val f = Fixture()
        f.wrapper.prepare(); f.wrapper.acceptedManifest(); f.wrapper.acceptedManifest()
        assertEquals(listOf("prepare", "preacquire", "requested"), f.calls)
        f.wrapper.release()
        assertEquals(listOf("prepare", "preacquire", "requested", "referenceRelease", "release"), f.calls)
    }
    @Test fun `inactive attempt cannot start acquisition`() {
        val f = Fixture()
        f.wrapper.prepare(); f.active = false; f.wrapper.acceptedManifest(); f.wrapper.release()
        assertEquals(listOf("prepare", "release"), f.calls)
    }
    @Test fun `release before acceptance never revives`() {
        val f = Fixture()
        f.wrapper.prepare(); f.wrapper.release(); f.wrapper.acceptedManifest()
        assertEquals(listOf("prepare", "release"), f.calls)
        assertThrows(IllegalStateException::class.java) { f.wrapper.prepare() }
        assertThrows(IllegalStateException::class.java) { f.wrapper.release() }
    }
    @Test fun `nested source preparation balances ownership without reacquisition`() {
        val f = Fixture()
        f.wrapper.prepare(); f.wrapper.prepare(); f.wrapper.acceptedManifest(); f.wrapper.release()
        assertFalse(f.calls.contains("referenceRelease"))
        f.wrapper.acceptedManifest(); f.wrapper.release()
        assertEquals(1, f.calls.count { it == "preacquire" })
        assertEquals(2, f.calls.count { it == "prepare" })
        assertEquals(2, f.calls.count { it == "release" })
        assertEquals(1, f.calls.count { it == "referenceRelease" })
    }
    @Test fun `ordinary playback acquisition and crypto checks still delegate`() {
        val f = Fixture()
        assertNull(f.wrapper.acquireSession(null, f.format))
        assertEquals(17, f.wrapper.getCryptoType(f.format))
        assertEquals(listOf("acquire", "crypto"), f.calls)
    }
    @Test fun `failed initial scheduling cannot retry`() {
        val f = Fixture()
        f.wrapper.prepare(); f.failAcquisition = true
        assertThrows(IllegalStateException::class.java) { f.wrapper.acceptedManifest() }
        f.failAcquisition = false; f.wrapper.acceptedManifest(); f.wrapper.release()
        assertEquals(listOf("prepare", "preacquire", "release"), f.calls)
    }
    @Test fun `failed held reference release still releases source manager`() {
        val f = Fixture()
        f.wrapper.prepare(); f.wrapper.acceptedManifest(); f.failRelease = true
        assertThrows(IllegalStateException::class.java) { f.wrapper.release() }
        f.wrapper.acceptedManifest()
        assertEquals(listOf("prepare", "preacquire", "requested", "referenceRelease", "release"), f.calls)
    }
}
