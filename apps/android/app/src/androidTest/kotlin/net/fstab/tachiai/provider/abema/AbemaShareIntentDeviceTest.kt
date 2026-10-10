package net.fstab.tachiai.provider.abema

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import android.text.SpannableString
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogIntent
import org.junit.Assert.*
import org.junit.Test

class AbemaShareIntentDeviceTest {
    private val channel = "https://abema.tv/channels/sumo"
    private fun share(text: String = channel) = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)

    @Test fun bareChannelSlotEpisodeAndSeriesShareTheExistingExactPublicIdentityRules() {
        val cases = listOf(channel to CatalogIntent.CHANNEL,
            "https://abema.tv/channels/sumo/slots/FutureFixture118" to CatalogIntent.BROADCAST,
            "https://abema.tv/video/episode/NeverLive118" to CatalogIntent.VIDEO,
            "https://abema.tv/video/title/394-72" to CatalogIntent.COLLECTION)
        cases.forEach { (url, kind) ->
            val entry = checkNotNull(abemaSharedEntry(share(url)))
            assertEquals(parseAbemaPublicResource(url), entry)
            assertEquals(kind, entry.resource.intent)
            assertEquals(CatalogAvailability.UNKNOWN, entry.availability)
            assertNull(entry.scheduledStartEpochMs)
        }
        val normalized = share(" HTTPS://ABEMA.TV:443/channels/sumo ")
            .putExtra("PROVIDER_INSTANCE_ID", "sender-selected-instance")
            .putExtra(Intent.EXTRA_SUBJECT, "Ignored sender title")
        assertEquals(parseAbemaPublicResource(channel), abemaSharedEntry(normalized))
    }

    @Test fun oneMatchingPlainTextClipIsAllowedWithoutResolvingAnything() {
        val delivered = share().apply { clipData = ClipData.newPlainText("Ignored label", channel) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(parseAbemaPublicResource(channel), abemaSharedEntry(delivered))
    }

    @Test fun unsupportedActionsMimeTypesDataSelectorsAndAttachmentsAreRejected() {
        val requests = listOf(
            share().setAction(Intent.ACTION_SEND_MULTIPLE),
            share().setAction(Intent.ACTION_VIEW),
            share().setAction(Intent.ACTION_MAIN),
            share().setAction(null),
            share().setType(null),
            share().setType("text/html"),
            share().setType("application/octet-stream"),
            share().setType("text/plain; charset=utf-8"),
            share().setDataAndType(Uri.parse(channel), "text/plain"),
            share().apply { selector = Intent(Intent.ACTION_VIEW, Uri.parse(channel)) },
            share().putExtra(Intent.EXTRA_STREAM, Uri.parse("content://fixture/private-file")),
            share().putExtra(Intent.EXTRA_STREAM, null as Parcelable?),
            share().putExtra(Intent.EXTRA_HTML_TEXT, "<a href='$channel'>fixture</a>"),
            share().putExtra(Intent.EXTRA_HTML_TEXT, null as String?),
        )
        requests.forEach { assertNull(abemaSharedEntry(it)) }
    }

    @Test fun conflictingMultipleRichAndUriClipItemsAreRejected() {
        val clips = listOf(
            ClipData.newPlainText("fixture", "https://abema.tv/channels/news"),
            ClipData.newPlainText("fixture", channel).apply { addItem(ClipData.Item(channel)) },
            ClipData.newRawUri("fixture", Uri.parse("content://fixture/private-file")),
            ClipData.newRawUri("fixture", Uri.parse(channel)),
            ClipData.newIntent("fixture", Intent(Intent.ACTION_VIEW, Uri.parse(channel))),
            ClipData.newHtmlText("fixture", channel, "<a href='$channel'>fixture</a>"),
            ClipData("fixture", arrayOf("text/html"), ClipData.Item(channel)),
            ClipData.newPlainText("fixture", SpannableString(channel)),
            ClipData("fixture", arrayOf("text/plain"), ClipData.Item(channel, null,
                Intent(Intent.ACTION_VIEW, Uri.parse(channel)), null)),
        )
        clips.forEach { clip -> assertNull(abemaSharedEntry(share().apply { clipData = clip })) }
    }

    @Test fun onlyBoundedBarePublicUrlTextIsAcceptedAndSensitiveUrlsStayRejected() {
        listOf("", "x".repeat(2049), "$channel\n", "$channel\t", "$channel\u200B",
            "Fixture title\n$channel", "$channel https://abema.tv/channels/news",
            "$channel?token=unvalidated-fixture", "$channel#private-fixture",
            "https://fixture-user:fixture-password@abema.tv/channels/sumo",
            "https://abema.tv/account", "https://abema.tv.evil.test/channels/sumo",
            "https://abema.tv/channels/%73umo", "http://abema.tv/channels/sumo")
            .forEach { assertNull(abemaSharedEntry(share(it))) }
        assertNull(abemaSharedEntry(shareWithoutText()))
        assertNull(abemaSharedEntry(share().putExtra(Intent.EXTRA_TEXT, 118)))
        assertNull(abemaSharedEntry(share().putExtra(Intent.EXTRA_TEXT, SpannableString(channel))))
        assertNull(abemaSharedEntry(share().putExtra(Intent.EXTRA_TEXT, StringBuilder(channel) as CharSequence)))
    }

    private fun shareWithoutText() = Intent(Intent.ACTION_SEND).setType("text/plain")

    @Test fun unparcellingFailureReturnsOnlyARejectedEntry() {
        val inaccessible = object : Intent(Intent.ACTION_SEND) {
            override fun getCharSequenceExtra(name: String): CharSequence? =
                throw BadParcelableException("Synthetic shared-text parcel failure")
        }.setType("text/plain")
        assertNull(abemaSharedEntry(inaccessible))
        val original = share().putExtra(Intent.EXTRA_TEXT, BrokenText())
        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val delivered = Intent.CREATOR.createFromParcel(parcel)
            delivered.setExtrasClassLoader(BrokenText::class.java.classLoader)
            assertNull(abemaSharedEntry(delivered))
        } finally { parcel.recycle() }
    }

    class BrokenText : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(destination: Parcel, flags: Int) { destination.writeInt(118) }
        companion object {
            @JvmField val CREATOR = object : Parcelable.Creator<BrokenText> {
                override fun createFromParcel(source: Parcel): BrokenText = throw BadParcelableException("Synthetic shared-text parcel failure")
                override fun newArray(size: Int): Array<BrokenText?> = arrayOfNulls(size)
            }
        }
    }
}
