package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbemaConfiguredEpisodeBindingTest {
    private fun source(id: String) = "https://ds-vod-abematv.akamaized.net/program/$id/manifest.mpd?t=synthetic"
    private fun binding(id: String, uri: String = source(id)) = "{\"episodeId\":${JSONObject.quote(id)},\"uri\":${JSONObject.quote(uri)}}"

    @Test fun twoExactProgramsHaveIndependentMetadataAndNativeSourceAdmission() {
        for (id in listOf("394-72_s10_p8510", "fixture-series_p2")) {
            assertEquals(AbemaReplayUriPolicy.ALLOWED,
                abemaReplaySelectedSourcePolicy(URI(source(id)), AbemaReplayCdnScope.APPROVED_PROTOTYPE, id))
            assertEquals(URI(source(id)), parseAbemaEpisodeSourceBinding(binding(id), id))
            val metadata = URI("https://api.p-c3-e.abema-tv.com/v1/video/programs/$id")
            assertTrue(AbemaNativeBootstrapPolicy.allows(metadata, false, "GET", id))
            assertTrue(AbemaNativeBootstrapPolicy.allows(metadata, false, "OPTIONS", id))
            val csp = AbemaNativeBootstrapPolicy.contentSecurityPolicy(id)
            assertTrue(csp.contains(metadata.toString())); assertFalse(csp.contains("api.abema.io"))
            val license = URI("https://license.p-c3-e.abema-tv.com/abematv-dash?t=synthetic&ct=program&cid=$id")
            assertTrue(AbemaNativeBootstrapPolicy.allows(license, false, "POST", id))
            assertTrue(AbemaNativeBootstrapPolicy.allows(license, false, "OPTIONS", id))
            assertFalse(AbemaNativeBootstrapPolicy.allows(license, false, "GET", id))
        }
    }

    @Test fun selectedSourceAndInitialLicenseCannotAcquireAnotherEpisodeOrHistoricalFallback() {
        val id = "394-72_s10_p8510"
        for (other in listOf("394-72_s10_p8529", "$id-extra")) {
            assertEquals(AbemaReplayUriPolicy.SOURCE_ROUTE,
                abemaReplaySelectedSourcePolicy(URI(source(other)), AbemaReplayCdnScope.APPROVED_PROTOTYPE, id))
            assertNull(parseAbemaEpisodeSourceBinding(binding(id, source(other)), id))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://api.p-c3-e.abema-tv.com/v1/video/programs/$other"), false, "GET", id))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://license.p-c3-e.abema-tv.com/abematv-dash?t=synthetic&ct=program&cid=$other"), false, "POST", id))
        }
        for (uri in listOf("https://api.abema.io/v1/channels", "https://api.abema.io/v1/video/programs/$id",
            "https://abema.tv/video/episode/$id", "https://api.p-c3-e.abema-tv.com/v1/video/programs/$id?fallback=true",
            "https://api.p-c3-e.abema-tv.com/v1/users/synthetic"))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(uri), false, "GET", id))
        for (query in listOf("t=synthetic", "t=synthetic&ct=channel&cid=$id", "t=synthetic&ct=program&cid=$id&cid=$id"))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://license.p-c3-e.abema-tv.com/abematv-dash?$query"), false, "POST", id))
    }

    @Test fun ownedProjectionRejectsAmbiguousTrailingExtraWrongIdentityAndUnsafeUri() {
        val id = "394-72_s10_p8510"
        val valid = binding(id)
        for (text in listOf(valid + " trailing", valid.dropLast(1) + ",\"episodeId\":\"$id\"}",
            valid.dropLast(1) + ",\"extra\":true}", binding("other"), binding(id, source(id) + "#fragment"),
            binding(id, source(id).replace("https:", "http:")), binding(id, source(id).replace("/program/", "/program/%2e%2e/")),
            "{episodeId:'$id',uri:'${source(id)}'}")) assertNull(parseAbemaEpisodeSourceBinding(text, id))
        assertNull(parseAbemaEpisodeSourceBinding(null, id))
        assertNull(parseAbemaEpisodeSourceBinding("x".repeat(4097), id))
    }
}
