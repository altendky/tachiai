package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbemaSlotSourceBindingTest {
    private val channel = "channel-A"
    private val slot = "slot-A"
    private fun source(id: String = slot, query: String = "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0") =
        "https://ds-vod-abematv.akamaized.net/provider-declared/$id/manifest.mpd?$query"
    private fun binding(channelId: String = channel, slotId: String = slot, uri: String = source(slotId)) =
        "{\"channelId\":${JSONObject.quote(channelId)},\"slotId\":${JSONObject.quote(slotId)},\"uri\":${JSONObject.quote(uri)}}"

    @Test fun twoExactSlotAndChannelPairsBindCanonicalOwnedProjectionWithoutAssumingAProviderRoot() {
        // Literal JSON.stringify output, independent of desktop/Android quote().
        val owned = """{"channelId":"channel-A","slotId":"slot-A","uri":"https://ds-vod-abematv.akamaized.net/provider-declared/slot-A/manifest.mpd?t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0"}"""
        assertEquals(URI(source()), parseAbemaSlotSourceBinding(owned, channel, slot))
        assertNull(parseAbemaSlotSourceBinding(owned.replace("/", "\\/"), channel, slot))
        for ((channelId, slotId) in listOf(channel to slot, "other-channel" to "other-slot")) {
            val uri = URI(source(slotId))
            assertEquals(uri, parseAbemaSlotSourceBinding(binding(channelId, slotId), channelId, slotId))
            assertTrue(allowsAbemaSlotSource(uri, channelId, slotId))
            assertNull(parseAbemaSlotSourceBinding(binding(channelId, slotId), "different-channel", slotId))
            assertNull(parseAbemaSlotSourceBinding(binding(channelId, slotId), channelId, "different-slot"))
        }
        assertTrue(allowsAbemaSlotSource(URI(source().replace("/provider-declared/", "/another/exact/")), channel, slot))
        for (id in listOf("$slot-extra", "prefix-$slot", "$slot.mpd"))
            assertFalse(allowsAbemaSlotSource(URI(source(id)), channel, slot))
        assertFalse(allowsAbemaSlotSource(URI(source().replace("/$slot/", "/different/") + "&fixture=$slot"), channel, slot))
    }
    @Test fun ownedJsonRejectsDuplicateEscapedKeysNoncanonicalEscapesWrongTypesExtrasAndMalformedUnicode() {
        val valid = binding()
        for (text in listOf(valid + " ", valid + "{}", " $valid", valid.replace(",\"slotId\"", ", \"slotId\""),
            valid.replace("\"channelId\":", "\"channelId\":\"$channel\",\"channelId\":"),
            valid.replace("\"channelId\":", "\"channel\\u0049d\":\"$channel\",\"channelId\":"),
            valid.replace("\"channelId\"", "\"channel\\u0049d\""),
            valid.replace("\"slotId\":\"$slot\"", "\"slotId\":12"), valid.replace("\"slotId\":\"$slot\"", "\"slotId\":null"),
            valid.replace("\"channel-A\"", "\"\\u0063hannel-A\""), valid.replace("https://", "https:\\/\\/"),
            valid.dropLast(1) + ",\"grant\":\"fixture\"}", valid.replace("fixture-token", "\\ud800"),
            "{channelId:'$channel',slotId:'$slot',uri:'${source()}'}", "x".repeat(4097)))
            assertNull(parseAbemaSlotSourceBinding(text, channel, slot))
        assertNull(parseAbemaSlotSourceBinding(null, channel, slot))
    }
    @Test fun selectedUriRefusesLookalikesUnsafeAuthoritiesFragmentsEncodedAndNormalizedPaths() {
        for (candidate in listOf(source().replace("https:", "http:"), source().replace(".net/", ".net:443/"),
            source().replace(".net/", ".net:444/"), source().replace("https://", "https://@"),
            source().replace("https://", "https://fixture:secret@"), source() + "#", source() + "#fixture",
            source().replace("ds-vod-abematv.akamaized.net", "ds-vod-abematv.akamaized.net.evil.invalid"),
            source().replace("ds-vod-abematv.akamaized.net", "nested.ds-vod-abematv.akamaized.net"),
            source().replace("ds-vod-abematv.akamaized.net", "other.akamaized.net"),
            source().replace("/manifest", "/../manifest"), source().replace("/manifest", "/./manifest"),
            source().replace("/manifest", "/%2e%2e/manifest"), source().replace(slot, "%73lot-A"),
            source().replace(".mpd", ".m3u8"), source().replace(".mpd", ".MPD"),
            source().replace("manifest.mpd", "manifest\\.mpd"), source().replace("manifest.mpd", "manifest\u202e.mpd"))) {
            assertNull(parseAbemaSlotSourceBinding(binding(uri = candidate), channel, slot))
        }
        assertEquals(AbemaSlotUriPolicy.SIZE, abemaSlotSelectedSourcePolicy(URI(source(query = "t=" + "a".repeat(2049))), channel, slot))
    }
    @Test fun initialLegacyQueryIsClosedAndCanonicalButDoesNotInferTokenFreshness() {
        for (query in listOf("t=fixture-token&enc=clear&dt=pc_unknown&ut=1&ccf=0",
            "ccf=0&ut=1&dt=pc_chrome&enc=clear&t=fixture-token&sw=0",
            "t=opaque%2Ftoken%2B%3D&enc=clear&dt=pc_chrome&ut=1&ccf=0"))
            assertTrue(allowsAbemaSlotSource(URI(source(query = query)), channel, slot))
        for (query in listOf("", "t=fixture-token", "t=&enc=clear&dt=pc_chrome&ut=1&ccf=0",
            "t=fixture-token&enc=wv&dt=pc_chrome&ut=1&ccf=0", "t=fixture-token&enc=clear&dt=web_android&ut=1&ccf=0",
            "t=fixture-token&enc=clear&dt=PC+Chrome%2Funknown&ut=1&ccf=0", "t=fixture-token&enc=clear&dt=pc_chrome&ut=2&ccf=0",
            "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=1", "t=fixture-token&enc=clear&dt=pc_chrome&ut=1",
            "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0&sw=1", "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0&t=other",
            "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0&redirect=fixture", "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0&",
            "%74=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0", "t=%66ixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0",
            "t=%0Afixture&enc=clear&dt=pc_chrome&ut=1&ccf=0", "t=%FF&enc=clear&dt=pc_chrome&ut=1&ccf=0"))
            assertFalse(allowsAbemaSlotSource(URI(source(query = query)), channel, slot))
    }
    @Test fun ownerIdentityKeepsExisting64CharacterLeaves() {
        assertTrue(allowsAbemaSlotSource(URI(source("a".repeat(64))), "c".repeat(64), "a".repeat(64)))
        for ((channelId, slotId) in listOf("c".repeat(65) to slot, channel to "s".repeat(65), channel to "bad/id",
            "bad/id" to slot, channel to "-slot", "" to slot))
            assertFalse(allowsAbemaSlotSource(URI(source()), channelId, slotId))
    }

    @Test fun canonicalModeIsExplicitAndOldEntryPointRemainsOrdinaryOnly() {
        for ((channelId, slotId) in listOf(channel to slot, "other-channel" to "other-slot")) {
            val ordinary = binding(channelId, slotId)
            val chase = ordinary.dropLast(1) + ",\"chase\":true}"
            val normalResult = checkNotNull(parseAbemaSlotPlaybackBinding(ordinary, channelId, slotId))
            val chaseResult = checkNotNull(parseAbemaSlotPlaybackBinding(chase, channelId, slotId))
            assertEquals(URI(source(slotId)), normalResult.uri); assertFalse(normalResult.chase)
            assertEquals(normalResult.uri, chaseResult.uri); assertTrue(chaseResult.chase)
            assertEquals(normalResult.uri, parseAbemaSlotSourceBinding(ordinary, channelId, slotId))
            assertNull(parseAbemaSlotSourceBinding(chase, channelId, slotId))
            // The same URI parses under both explicit modes. This projection
            // carries no account, subscription or content-authority receipt.
            assertEquals("AbemaSlotPlaybackBinding(redacted)", chaseResult.toString())
            assertFalse(chaseResult.toString().contains("fixture-token"))
        }
    }

    @Test fun chaseModeRejectsCoercionsFalseDuplicateExtraAndReorderedFields() {
        val ordinary = binding()
        val chase = ordinary.dropLast(1) + ",\"chase\":true}"
        val invalid = listOf("false", "null", "1", "\"true\"", "[]", "{}")
            .map { ordinary.dropLast(1) + ",\"chase\":$it}" } + listOf(
                chase + " ", " $chase", chase + "{}",
                chase.replace(",\"chase\":true", ", \"chase\":true"),
                chase.dropLast(1) + ",\"chase\":true}",
                chase.dropLast(1) + ",\"authority\":true}",
                chase.replace("\"chase\"", "\"cha\\u0073e\""),
                chase.replace(",\"chase\":true", "").replace("{", "{\"chase\":true,"),
                chase.replace("\"channelId\":", "\"channelId\":\"$channel\",\"channelId\":"),
                chase.replace("fixture-token", "\\ud800"), chase.replace("https://", "https:\\/\\/"),
                "x".repeat(4097))
        for (value in invalid) {
            assertNull(parseAbemaSlotPlaybackBinding(value, channel, slot))
            assertNull(parseAbemaSlotSourceBinding(value, channel, slot))
        }
        assertNull(parseAbemaSlotPlaybackBinding(null, channel, slot))
    }

    @Test fun chaseBindingRetainsExactOwnersAndExistingSourceQueryPolicy() {
        fun chase(uri: String = source()) = binding(uri = uri).dropLast(1) + ",\"chase\":true}"
        assertNull(parseAbemaSlotPlaybackBinding(chase(), "other-channel", slot))
        assertNull(parseAbemaSlotPlaybackBinding(chase(), channel, "other-slot"))
        for (candidate in listOf(source("other-slot"), source().replace("https:", "http:"),
            source().replace(".net/", ".net:443/"), source().replace("https://", "https://@"),
            source().replace("ds-vod-abematv.akamaized.net", "ds-vod-abematv.akamaized.net.evil.invalid"),
            source().replace("/manifest", "/../manifest"), source().replace("/manifest", "/%2e%2e/manifest"),
            source() + "#", source().replace(".mpd", ".m3u8"),
            source(query = "t=fixture-token&enc=wv&dt=pc_chrome&ut=1&ccf=0"),
            source(query = "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0&chase=true"),
            source(query = "t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0&t=other"),
            source(query = "t=" + "a".repeat(2049))))
            assertNull(parseAbemaSlotPlaybackBinding(chase(candidate), channel, slot))
        val ordinaryWithModeLikePath = binding(uri = source().replace("/provider-declared/", "/chase/"))
        assertFalse(checkNotNull(parseAbemaSlotPlaybackBinding(ordinaryWithModeLikePath, channel, slot)).chase)
    }
}
