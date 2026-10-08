package net.fstab.tachiai.platform.network

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ConnectionImportIntentTest {
    private val packageName = "net.fstab.tachiai"
    private val uri = Uri.parse("content://net.fstab.fixture.connections/configuration")
    private fun view() = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    private fun share() = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @Test fun shareOpenAndPickerResolveTheSameConfigurationWithoutPersistingAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val open = view().apply { clipData = ClipData.newRawUri("fixture", uri) }
        val send = share().apply { clipData = ClipData.newRawUri("fixture", uri) }
        assertEquals(uri, connectionImportUri(open, packageName))
        assertEquals(uri, connectionImportUri(send, packageName))
        val picker = ActivityResultContracts.OpenDocument()
        val request = picker.createIntent(context, arrayOf("text/plain", "application/octet-stream"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.action)
        assertEquals("*/*", request.type)
        assertArrayEquals(arrayOf("text/plain", "application/octet-stream"), request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertEquals(uri, picker.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(picker.parseResult(Activity.RESULT_CANCELED, null))
        checkConnectionImportUri(uri, packageName)
    }

    @Test fun alreadyReadableAttachmentsCanIncludeDescriptionsWithoutParsingThem() {
        assertEquals(uri, connectionImportUri(share().setFlags(0).putExtra(Intent.EXTRA_TEXT, "https://ignored.example.test"), packageName))
        assertEquals(uri, connectionImportUri(view().setFlags(0).putExtra(Intent.EXTRA_TEXT, "ignored description"), packageName))
    }

    @Test fun arbitraryUrisAndAmbiguousPayloadsAreRejected() {
        val badUris = listOf("https://example.test/config.conf", "file:///sdcard/config.conf",
            "content://net.fstab.tachiai.private/config", "content://user:password@fixture/config")
        badUris.forEach {
            assertThrows(Exception::class.java) { connectionImportUri(view().setData(Uri.parse(it)), packageName) }
        }
        listOf(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "page link"),
            share().setData(Uri.parse("content://different/file")), Intent(Intent.ACTION_SEND_MULTIPLE),
            view().putExtra(Intent.EXTRA_STREAM, uri),
            share().apply { clipData = ClipData.newRawUri("fixture", uri).apply { addItem(ClipData.Item(uri)) } },
            view().apply { clipData = ClipData.newRawUri("fixture", Uri.parse("content://different/file")) })
            .forEach { assertThrows(Exception::class.java) { connectionImportUri(it, packageName) } }
    }
}
