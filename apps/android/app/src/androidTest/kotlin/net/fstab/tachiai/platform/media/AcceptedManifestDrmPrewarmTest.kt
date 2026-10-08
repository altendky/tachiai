package net.fstab.tachiai.platform.media

import android.media.MediaDrm
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.provider.abema.commonPssh
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AcceptedManifestDrmPrewarmTest {
    @Test fun releaseBeforeSdkQueuedAcquisitionMakesNoLicenseRequest() {
        assumeTrue(MediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID))
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        val exchanges = AtomicInteger()
        val callback = object : MediaDrmCallback {
            override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response {
                exchanges.incrementAndGet(); throw IOException("fixture refused")
            }
            override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
                exchanges.incrementAndGet(); throw IOException("fixture refused")
            }
        }
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false).setSessionKeepaliveMs(C.TIME_UNSET)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)).build(callback)
        val fixturePssh = commonPssh(listOf(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")))
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
            .setDrmInitData(DrmInitData(C.CENC_TYPE_cenc, DrmInitData.SchemeData(C.COMMON_PSSH_UUID,
                MimeTypes.VIDEO_MP4, fixturePssh)))
            .build()
        val thread = HandlerThread("tachiai-prewarm-cancellation-fixture").apply { start() }
        val complete = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        var active = true
        val wrapper = AcceptedManifestDrmPrewarm(manager, format, null, { active }) { }
        val handler = Handler(thread.looper)
        try {
            handler.post {
                try {
                    wrapper.setPlayer(thread.looper, PlayerId.UNSET)
                    wrapper.prepare()
                    try {
                        wrapper.acceptedManifest() // SDK queues acquisition behind this callback.
                    } finally {
                        active = false
                        wrapper.release() // Release before the SDK's queued work can execute.
                    }
                    handler.post { complete.countDown() } // Barrier after the queued acquisition.
                } catch (error: Throwable) { failure.set(error); complete.countDown() }
            }
            assertTrue("Playback-thread fixture completed", complete.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Playback-thread fixture failed", it) }
            assertEquals(0, exchanges.get())
        } finally {
            thread.quitSafely()
            thread.join(5000)
        }
    }
}
