package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Looper
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit, read-only intended-account acceptance. Default instrumentation skips
// before reading stores or constructing a service/transport. Run only after the
// owner finishes Connect, with a separate protected-record baseline. No UI,
// token polling, configured-item mutation or provider field export occurs here.
class AbemaMyListDeviceTest {
    @Test fun optInSavedAccountReadsOneMyListPageWithoutChangingStores() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("abemaReadOnlyMyList") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Application.getProcessName() == context.packageName)
        val instanceId = args.getString("abemaProviderInstance") ?: error("Explicit provider instance required")
        require(validProviderInstanceId(instanceId))
        check(Looper.myLooper() != Looper.getMainLooper())
        fun instances() = providerInstanceStore(context).read { legacyProviderSettings(context) }
        val before = instances()
        val owner = before.single { it.id == instanceId && it.service == PrototypeService.ABEMA }
        assertTrue("Selected route must be explicit", owner.setup.route?.mode in
            setOf(SourceRouteMode.SYSTEM, SourceRouteMode.SAVED_CONNECTION))
        val setups = sourceSetupStore(context).read()
        val accountFile = File(context.noBackupFilesDir, "abema-account-$instanceId.enc")
        assertTrue("Owner-approved protected grant must exist", accountFile.isFile)
        fun encryptedRecord(): ByteArray {
            check(accountFile.length() in 1..65536)
            return accountFile.readBytes().also { check(it.size in 1..65536) }
        }
        val protectedBefore = encryptedRecord()
        try {
            androidAbemaChannelCatalog(context, instanceId, setups).use { catalog ->
                val access = catalog.capabilities().collections.single { it.id == "my_list" }.access
                if (access != CatalogAccess.AVAILABLE) {
                    Log.i("TachiaiAbemaMyList", "MY_LIST_ACCESS_${access.name}")
                    fail("Intended-account collection access was not established")
                }
                when (val result = catalog.browse(CatalogQuery(collectionId = "my_list"))) {
                    is CatalogResult.Failure -> {
                        Log.i("TachiaiAbemaMyList", "MY_LIST_FAILURE_${result.reason.name}")
                        fail("Intended-account collection request did not succeed")
                    }
                    is CatalogResult.Value -> {
                        val page = result.value
                        assertTrue(page.entries.size <= 20)
                        assertTrue(page.entries.all { it.resource.providerId == ProviderId("abema") &&
                            it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
                        assertTrue(page.entries.map { it.resource }.distinct().size == page.entries.size)
                        Log.i("TachiaiAbemaMyList", "MY_LIST_PAGE_ACCEPTED count=${page.entries.size} hasNext=${page.nextCursor != null}")
                    }
                }
            }
        } finally {
            val protectedAfter = encryptedRecord()
            try { assertTrue("Protected grant must remain unchanged", protectedBefore.contentEquals(protectedAfter)) }
            finally { protectedBefore.fill(0); protectedAfter.fill(0) }
            assertTrue("Provider registry must remain unchanged", before == instances())
            assertTrue("Source setup must remain unchanged", setups == sourceSetupStore(context).read())
        }
    }
}
