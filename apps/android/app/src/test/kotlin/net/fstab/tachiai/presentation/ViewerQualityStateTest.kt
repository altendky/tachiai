package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.*
import org.junit.Assert.*
import org.junit.Test

class ViewerQualityStateTest {
    private val video = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
    private val audio = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.AUDIO, NativeQualityCodec.AAC, 192_000,
        channelCount = 2, sampleRateHz = 48_000))
    private val source = PrototypeSource.ABEMA_REPLAY

    @Test fun feedOverridesTakePrecedenceAndExplicitAutoDiffersFromInheritance() {
        val state = ViewerQualityState(listOf(source, source), mapOf(source to NativeQualityPreferences(video, audio)))
        assertEquals(video, state.effective(NativeMixedSide.A).video)
        state.setOverride(NativeMixedSide.A, NativeQualityKind.VIDEO, NativeQualityRequest.auto)
        assertEquals(NativeQualityRequest.auto, state.effective(NativeMixedSide.A).video)
        assertEquals(video, state.effective(NativeMixedSide.B).video)
        assertEquals(audio, state.effective(NativeMixedSide.A).audio)
        state.setOverride(NativeMixedSide.A, NativeQualityKind.VIDEO, null)
        assertEquals(video, state.effective(NativeMixedSide.A).video)
        assertNull(state.read(NativeMixedSide.A, NativeQualityKind.VIDEO).feedOverride)
    }

    @Test fun savedDefaultsUpdateInheritingDuplicatesButNeverReplaceOverridesOrOtherKind() {
        val state = ViewerQualityState(listOf(source, source), emptyMap())
        state.setOverride(NativeMixedSide.B, NativeQualityKind.VIDEO, NativeQualityRequest.auto)
        state.replaceDefaults(mapOf(source to NativeQualityPreferences(video, audio)))
        assertEquals(video, state.effective(NativeMixedSide.A).video)
        assertEquals(NativeQualityRequest.auto, state.effective(NativeMixedSide.B).video)
        state.replaceDefaults(mapOf(source to NativeQualityPreferences(audio = audio)))
        assertEquals(NativeQualityRequest.auto, state.effective(NativeMixedSide.A).video)
        assertEquals(audio, state.effective(NativeMixedSide.B).audio)
    }

    @Test fun stoppingClearsOnlySessionOverridesAndNewSessionsInheritSavedDefaults() {
        val defaults = mapOf(source to NativeQualityPreferences(video, audio))
        val state = ViewerQualityState(listOf(source, PrototypeSource.TWITCH_LIVE), defaults)
        state.setOverride(NativeMixedSide.A, NativeQualityKind.AUDIO, NativeQualityRequest.auto)
        state.clearOverrides()
        assertEquals(audio, state.effective(NativeMixedSide.A).audio)
        assertEquals(NativeQualityPreferences(), state.effective(NativeMixedSide.B))
        assertEquals(state.effective(NativeMixedSide.A), ViewerQualityState(listOf(source, source), defaults).effective(NativeMixedSide.A))
    }

    @Test fun malformedAndWrongKindPreferencesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeQualityPreferences(video = audio) }
        assertThrows(IllegalArgumentException::class.java) { NativeQualityRequest(video.track!!.copy(width = 0)) }
        assertThrows(IllegalArgumentException::class.java) { NativeQualityRequest(audio.track!!.copy(codec = NativeQualityCodec.AVC)) }
        assertThrows(IllegalArgumentException::class.java) { NativeQualityRequest(video.track!!.copy(bitrateBps = Int.MAX_VALUE)) }
        assertThrows(IllegalArgumentException::class.java) {
            ViewerQualityState(listOf(source, source), emptyMap()).setOverride(NativeMixedSide.A, NativeQualityKind.VIDEO, audio)
        }
    }
}
