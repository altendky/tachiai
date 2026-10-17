package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbemaConfiguredChannelBindingTest {
    private fun source(id: String) = "https://linear-abematv.akamaized.net/channel/$id/manifest.mpd?t=fixture&enc=clear&dt=pc_chrome&ut=1"
    private fun binding(id: String, uri: String = source(id)) = "{\"channelId\":${JSONObject.quote(id)},\"uri\":${JSONObject.quote(uri)}}"

    @Test fun modernObservationRetainsReturnedDeviceQueryOnlyForExactClosedChannelBinding() {
        for (id in listOf("fixture-one", "fixture-two")) {
            for (query in listOf("", "&dtid=999", "&dtid=fixture-type%3A01")) {
                val uri = source(id) + query
                assertEquals(URI(uri), parseAbemaChannelSourceBinding(binding(id, uri), id, modernChannelContext = true))
                if (query.isNotEmpty()) assertNull(parseAbemaChannelSourceBinding(binding(id, uri), id))
                assertNull(parseAbemaChannelSourceBinding(binding(id, uri), "other", modernChannelContext = true))
            }
            for (query in listOf("&dtid=", "&dtid=first&dtid=second", "&dtid=secret%0Avalue", "&dtid=" + "x".repeat(257),
                "&%64tid=999", "&dtid=999&division=1", "&dtid=999#fragment"))
                assertNull(parseAbemaChannelSourceBinding(binding(id, source(id) + query), id, modernChannelContext = true))
            assertNull(parseAbemaChannelSourceBinding(binding(id, source("other") + "&dtid=999"), id, modernChannelContext = true))
            val extra = binding(id, source(id) + "&dtid=999").dropLast(1) + ",\"deviceTypeId\":999}"
            assertNull(parseAbemaChannelSourceBinding(extra, id, modernChannelContext = true))
        }
    }

    @Test fun exactChannelProjectionRootAndLicenseAdmitTwoIdsWithoutUnrelatedApis() {
        for (id in listOf("fixture-one", "fixture-two")) {
            assertEquals(URI(source(id)), parseAbemaChannelSourceBinding(binding(id), id))
            assertTrue(AbemaNativeBootstrapPolicy.allowsNewsSource(URI(source(id)), true, id))
            assertTrue(AbemaNativeBootstrapPolicy.allows(URI("https://api.abema.io/v1/channels"), false, "GET", channelId = id))
            assertTrue(AbemaNativeBootstrapPolicy.allows(URI("https://license.p-c3-e.abema-tv.com/abematv-dash?t=fixture&ct=channel&cid=$id"), false, "POST", channelId = id))
            assertFalse(AbemaNativeBootstrapPolicy.contentSecurityPolicy(channelId = id).contains("/playbackResources/"))
            assertFalse(AbemaNativeBootstrapPolicy.contentSecurityPolicy(channelId = id).contains("/video/programs/"))
            for (url in listOf("https://api.abema.io/v1/video/programs/394-72_s10_p8529", "https://api.p-c3-e.abema-tv.com/v1/video/programs/fixture",
                "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/$id", "https://abema.tv/now-on-air/$id"))
                assertFalse(AbemaNativeBootstrapPolicy.allows(URI(url), false, "GET", channelId = id))
        }
    }

    @Test fun ambiguousMalformedWrongIdentityRootAndLicenseAreRefused() {
        val id = "fixture-one"; val valid = binding(id)
        for (text in listOf(valid + "x", valid.dropLast(1) + ",\"channelId\":\"$id\"}",
            valid.dropLast(1) + ",\"extra\":true}", binding("other"), binding(id, source("other")),
            binding(id, source(id).replace("https:", "http:")), binding(id, source(id) + "#fragment"),
            binding(id, source(id).replace("/channel/", "/channel/%2e%2e/")), binding(id, source(id) + "&t=second")))
            assertNull(parseAbemaChannelSourceBinding(text, id))
        for (query in listOf("t=fixture&ct=program&cid=$id", "t=fixture&ct=channel&cid=other", "t=fixture&ct=channel&cid=$id&cid=$id",
            "t=fixture&ct=channel&cid=$id&pt=unverified"))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://license.p-c3-e.abema-tv.com/abematv-dash?$query"), false, "POST", channelId = id))
        assertNull(parseAbemaChannelSourceBinding(valid, "x".repeat(65)))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://api.abema.io/v1/channels"), false, "GET", "episode", id))
    }
}
