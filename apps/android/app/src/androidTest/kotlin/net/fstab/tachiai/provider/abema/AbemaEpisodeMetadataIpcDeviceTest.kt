package net.fstab.tachiai.provider.abema

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// The same opt-in guest-process instrumentation override as the owned JS
// fixtures is required. No provider request, saved-store access/reset, or
// default-process WebView/cookie mutation is performed by these fixtures.
class AbemaEpisodeMetadataIpcDeviceTest {
    private fun guestInstrumentation() = InstrumentationRegistry.getInstrumentation().also {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == "${it.targetContext.packageName}:abema_guest_bootstrap")
    }
    @Test fun slotParcelContractCannotBecomeAnEpisodeAndTypedFacadeRejectsOtherResourcesBeforePrivateReads() {
        val instrumentation = guestInstrumentation()
        val request = AbemaSlotMetadataIpc.Start("a".repeat(32), "12345678-1234-1234-1234-123456789abc",
            AbemaSlotRequest("channel-A", "slot-A"), "b".repeat(64), 46000)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(AbemaSlotMetadataIpc.start(request)); parcel.setDataPosition(0)
            val restored = checkNotNull(parcel.readBundle(javaClass.classLoader))
            val fields = checkNotNull(AbemaSlotMetadataIpc.fields(restored))
            assertEquals(request.slot.resource, AbemaSlotMetadataIpc.decodeStart(fields, 1000)?.slot?.resource)
            assertNull(AbemaEpisodeMetadataIpc.decodeStart(fields, 1000))
            restored.putParcelable("raw", Bundle()); assertNull(AbemaSlotMetadataIpc.fields(restored))
        } finally { parcel.recycle() }
        var reads = 0
        val client = AndroidAbemaSlotMetadataClient(instrumentation.targetContext, request.instanceId, request.ownerProof,
            { reads++; error("No private reads") }, { true })
        try {
            assertEquals(CatalogFailure.NOT_VERIFIED, (client.fetch(CatalogResource(ProviderId("abema"),
                "episode", "slot-A", CatalogIntent.VIDEO)) as CatalogResult.Failure).reason)
            val result = AtomicReference<CatalogResult<CatalogEntry>>()
            instrumentation.runOnMainSync { result.set(client.fetch(request.slot.resource)) }
            assertEquals(CatalogFailure.TEMPORARY, (result.get() as CatalogResult.Failure).reason); assertEquals(0, reads)
        } finally { client.close(); instrumentation.waitForIdleSync() }
    }

    @Test fun serviceIsPrivateSameGuestProcessAndBindingAloneDoesNotStartRuntime() {
        val instrumentation = guestInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, AbemaEpisodeMetadataService::class.java)
        val info = context.packageManager.getServiceInfo(component, 0)
        assertFalse(info.exported)
        assertEquals("${context.packageName}:abema_guest_bootstrap", info.processName)
        val bound = AtomicBoolean(); val connected = CountDownLatch(1)
        val idle = AtomicReference<Boolean>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                idle.set(!AbemaGuestRuntimeProcess.get(context).unavailable)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        try {
            instrumentation.runOnMainSync { bound.set(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)) }
            assertTrue(bound.get()); assertTrue(connected.await(10, TimeUnit.SECONDS)); assertEquals(true, idle.get())
        } finally {
            if (bound.get()) instrumentation.runOnMainSync { context.unbindService(connection) }
        }
    }

    @Test fun parcelRoundtripRetainsOnlyBoundedPrimitiveFieldsAndClientRejectsInvalidInputsBeforeBinding() {
        val instrumentation = guestInstrumentation()
        val start = AbemaEpisodeMetadataIpc.Start("a".repeat(32), "12345678-1234-1234-1234-123456789abc",
            "fixture-A_123", "b".repeat(64), 46_000)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(AbemaEpisodeMetadataIpc.start(start)); parcel.setDataPosition(0)
            val restored = checkNotNull(parcel.readBundle(javaClass.classLoader))
            val decoded = AbemaEpisodeMetadataIpc.decodeStart(checkNotNull(AbemaEpisodeMetadataIpc.fields(restored)), 1_000)
            assertEquals(start.episodeId, decoded?.episodeId); assertEquals(start.ownerProof, decoded?.ownerProof)
            restored.putByteArray("raw", byteArrayOf(1)); assertNull(AbemaEpisodeMetadataIpc.fields(restored))
            assertNull(AbemaEpisodeMetadataIpc.fields(Bundle().apply { putString("nonce", "x".repeat(257)) }))
        } finally { parcel.recycle() }
        var ownerReads = 0
        val client = AndroidAbemaEpisodeMetadataClient(instrumentation.targetContext, start.instanceId, start.ownerProof,
            canRequest = { ownerReads++; error("Invalid resource must not read protected owner") }, canRemainActive = { true })
        try {
            val invalid = CatalogResource(ProviderId("abema"), "channel", "fixture-channel", CatalogIntent.CHANNEL)
            assertEquals(CatalogFailure.NOT_VERIFIED, (client.fetch(invalid) as CatalogResult.Failure).reason)
            val onMain = AtomicReference<CatalogResult<CatalogEntry>>()
            instrumentation.runOnMainSync {
                onMain.set(client.fetch(CatalogResource(ProviderId("abema"), "episode", start.episodeId, CatalogIntent.VIDEO)))
            }
            assertEquals(CatalogFailure.TEMPORARY, (onMain.get() as CatalogResult.Failure).reason)
            assertEquals(0, ownerReads)
        } finally { client.close(); instrumentation.waitForIdleSync() }
    }
}
