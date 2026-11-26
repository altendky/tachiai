package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.dash.DashManifestStaleException
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

// #722: a fatal player error is named by code, stage and exception classes,
// never by a message; and a refused live manifest is reloaded before failing.
@UnstableApi
class NativePlayerFailureTest {
    private val secret = "https://linear.example/abema-news/manifest.mpd?token=secret"

    @Test fun `a refused live manifest is named by its class chain without any message`() {
        val error = ExoPlaybackException.createForSource(DashManifestStaleException(),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val failure = nativePlayerFailure(error)
        assertEquals(2000, failure.code)
        assertEquals("ERROR_CODE_IO_UNSPECIFIED", failure.codeName)
        assertEquals(NativePlayerFailureSource.SOURCE, failure.source)
        assertTrue(failure.manifestStale)
        assertEquals(listOf(ExoPlaybackException::class.java.name, DashManifestStaleException::class.java.name),
            failure.causeClasses)
        assertEquals("playerFailure code=2000 name=ERROR_CODE_IO_UNSPECIFIED source=SOURCE manifestStale=true " +
            "causes=androidx.media3.exoplayer.ExoPlaybackException>androidx.media3.exoplayer.dash.DashManifestStaleException",
            failure.closedSummary())
    }

    @Test fun `a live refresh the parser refused as out of sync reads as stale`() {
        val failure = nativePlayerFailure(ExoPlaybackException.createForSource(
            NativeDashManifestOutOfSyncException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertTrue(failure.manifestStale)
        assertEquals(NativePlayerFailureSource.SOURCE, failure.source)
    }

    @Test fun `messages URLs and tokens in the chain never reach the record`() {
        val error = ExoPlaybackException.createForSource(IOException(secret, IllegalStateException(secret)),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val summary = nativePlayerFailure(error).closedSummary()
        assertFalse(summary.contains("token") || summary.contains("http") || summary.contains("secret"))
        assertTrue(summary.endsWith("causes=androidx.media3.exoplayer.ExoPlaybackException>java.io.IOException>" +
            "java.lang.IllegalStateException"))
        assertFalse(nativePlayerFailure(error).manifestStale)
    }

    @Test fun `DRM failures are named as DRM whichever stage carried them`() {
        val drm = ExoPlaybackException.createForSource(IOException(DrmSession.DrmSessionException(IOException(secret),
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(NativePlayerFailureSource.DRM, nativePlayerFailure(drm).source)
        val code = ExoPlaybackException.createForUnexpected(RuntimeException(secret),
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR)
        assertEquals(NativePlayerFailureSource.DRM, nativePlayerFailure(code).source)
        val unexpected = ExoPlaybackException.createForUnexpected(RuntimeException(secret),
            PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertEquals(NativePlayerFailureSource.UNEXPECTED, nativePlayerFailure(unexpected).source)
    }

    @Test fun `the class chain is bounded and survives a cause cycle`() {
        val first = IOException(); val second = IOException(first)
        first.initCause(second)
        val failure = nativePlayerFailure(ExoPlaybackException.createForSource(first,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(3, failure.causeClasses.size)
        var deep: Throwable = IOException()
        repeat(20) { deep = IOException(deep) }
        assertEquals(NativePlayerFailure.MAX_CAUSES, nativePlayerFailure(ExoPlaybackException.createForSource(
            deep as IOException, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).causeClasses.size)
    }

    @Test fun `only live manifest refreshes are reloaded before the feed fails`() {
        val policy = NativeDashLoadErrorHandlingPolicy()
        assertEquals(NATIVE_DASH_MANIFEST_RELOADS, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        for (type in listOf(C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION, C.DATA_TYPE_DRM,
            C.DATA_TYPE_TIME_SYNCHRONIZATION, C.DATA_TYPE_UNKNOWN))
            assertEquals("type=$type", 0, policy.getMinimumLoadableRetryCount(type))
        // Everything else, including which errors are retried at all (a manifest
        // policy refusal never is), is the player's unchanged policy.
        val delegate = object : LoadErrorHandlingPolicy {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo) = 1234L
            override fun getMinimumLoadableRetryCount(dataType: Int) = 7
            override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions,
                info: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? = null
        }
        val wrapped = NativeDashLoadErrorHandlingPolicy(delegate)
        assertEquals(NATIVE_DASH_MANIFEST_RELOADS, wrapped.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        assertEquals(7, wrapped.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }
}
