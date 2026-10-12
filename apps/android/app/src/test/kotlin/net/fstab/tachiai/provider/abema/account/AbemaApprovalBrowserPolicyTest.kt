package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaApprovalBrowserPolicyTest {
    private val instanceId = "00000000-0000-0000-0000-000000000001"
    private val proof = "0123456789abcdef".repeat(4)
    private val verification = "https://abema.tv/account/change-from-device?code=fixture-code"

    @Test fun handoffPreservesOwnerAndOriginalDeadlineWithoutExposingPrivateValues() {
        val request = AbemaApprovalBrowserRequest(instanceId, proof, verification, 1000)
        assertEquals(instanceId, request.instanceId); assertEquals(proof, request.ownerProof)
        assertEquals(verification, request.verificationUri); assertEquals(1000L, request.deadlineElapsedMs)
        assertFalse(request.current(-1)); assertTrue(request.current(0)); assertTrue(request.current(999))
        assertFalse(request.current(1000)); assertFalse(request.current(Long.MAX_VALUE))
        assertEquals("AbemaApprovalBrowserRequest(redacted)", request.toString())
        assertFalse(AbemaApprovalBrowserRequest(instanceId, proof, verification, 0).current(0))
        assertTrue(AbemaApprovalBrowserRequest(instanceId, proof, verification, Long.MAX_VALUE).current(Long.MAX_VALUE - 1))
    }

    @Test fun malformedOwnerAndUnreviewedInitialDestinationCannotStartAHandoff() {
        for (id in listOf("", "abema", "0-0-0-0-1", " $instanceId"))
            assertThrows(IllegalArgumentException::class.java) { AbemaApprovalBrowserRequest(id, proof, verification, 1000) }
        for (invalid in listOf("", proof.uppercase(), proof.drop(1), proof + "0", "g".repeat(64)))
            assertThrows(IllegalArgumentException::class.java) { AbemaApprovalBrowserRequest(instanceId, invalid, verification, 1000) }
        for (url in listOf("https://auth.abema.tv/", "https://abema.tv:443/", "$verification#step", "https://other.invalid/"))
            assertThrows(IllegalArgumentException::class.java) { AbemaApprovalBrowserRequest(instanceId, proof, url, 1000) }
        assertThrows(IllegalArgumentException::class.java) { AbemaApprovalBrowserRequest(instanceId, proof, verification, -1) }
    }

    @Test fun providerPagesAllowNormalQueriesAndFragmentsAtExactStandardHttpsOrigins() {
        for (host in listOf("abema.tv", "auth.abema.tv")) for (port in listOf("", ":443")) {
            for (path in listOf("", "/", "/account?email=fixture%40example.test&next=%2Faccount#step",
                "/account?email=fixture+tag%40example.test#%E6%89%BF%E8%AA%8D", "/api/auth/login/email")) {
                val url = "https://$host$port$path"
                assertTrue(url, AbemaApprovalBrowserPolicy.allowedNavigation(url))
                assertTrue(url, AbemaApprovalBrowserPolicy.allowedResource(url))
            }
        }
        val api = "https://api.p-c3-e.abema-tv.com:443/v1/users/fixture?include=profile"
        assertTrue(AbemaApprovalBrowserPolicy.allowedResource(api))
        assertFalse(AbemaApprovalBrowserPolicy.allowedNavigation(api))
        assertEquals("abema-provider-approval", AbemaApprovalBrowserPolicy.PROFILE_SUFFIX)
        assertEquals(setOf(
            "abema.tv",
            "auth.abema.tv",
            "*.abema-tv.com",
            "ds-glb-linear-abematv.akamaized.net",
            "ds-linear-abematv.akamaized.net",
        ),
            AbemaApprovalBrowserPolicy.HOSTS.split(',').toSet())
    }

    @Test fun providerRegionCheckIsAnExactGetResourceWithoutCdnNavigationOrMedia() {
        for (host in listOf("ds-glb-linear-abematv.akamaized.net", "ds-linear-abematv.akamaized.net")) {
            val origin = "https://$host"
            assertTrue(AbemaApprovalBrowserPolicy.allowedResource("$origin/region", "GET"))
            assertFalse(AbemaApprovalBrowserPolicy.allowedNavigation("$origin/region"))
            assertFalse(AbemaApprovalBrowserPolicy.allowedResource("$origin:443/region"))
            for (method in listOf("POST", "HEAD", "OPTIONS", "get", ""))
                assertFalse(method, AbemaApprovalBrowserPolicy.allowedResource("$origin/region", method))
            for (suffix in listOf("", "/", "/region/", "//region", "/%72egion", "/region?fixture=value",
                "/region#fixture", "/channel/fixture/playlist.m3u8", "/media/fixture"))
                assertFalse(suffix, AbemaApprovalBrowserPolicy.allowedResource(origin + suffix))
            assertFalse(AbemaApprovalBrowserPolicy.allowedResource("$origin.other.invalid/region"))
            assertFalse(AbemaApprovalBrowserPolicy.allowedResource("http://$host/region"))
        }
        assertTrue(AbemaApprovalBrowserPolicy.allowedResource("https://abema.tv/api/auth/login/email", "POST"))
    }

    @Test fun providerOwnedResourceSubdomainsSupportStartupAndKeepNavigationSeparate() {
        for (host in listOf("gateway.p-c2-x.abema-tv.com", "api.p-c3-e.abema-tv.com", "image.p-c2-x.abema-tv.com",
            "media.p-c3-e.abema-tv.com", "license.p-c3-e.abema-tv.com", "streaming-api-cf.p-c2-x.abema-tv.com",
            "new-api.abema-tv.com", "nested.new-api.abema-tv.com")) {
            for (method in listOf("GET", "OPTIONS", "POST", "HEAD", "PUT", "DELETE"))
                assertTrue(AbemaApprovalBrowserPolicy.allowedResource("https://$host/resource?fixture=value", method))
            assertTrue(AbemaApprovalBrowserPolicy.allowedResource("https://$host:443/resource"))
            assertFalse(AbemaApprovalBrowserPolicy.allowedNavigation("https://$host/resource"))
        }
        for (url in listOf("https://abema-tv.com/web", "https://evilabema-tv.com/web",
            "https://gateway.p-c2-x.abema-tv.com.other.invalid/web", "https://abema-tv.com.evil.invalid/web",
            "https://-api.abema-tv.com/web", "https://api-.abema-tv.com/web",
            "https://${"a".repeat(64)}.abema-tv.com/web", "https://api..abema-tv.com/web",
            "http://gateway.p-c2-x.abema-tv.com/web", "https://user@gateway.p-c2-x.abema-tv.com/web",
            "https://gateway.p-c2-x.abema-tv.com:444/web")) {
            assertFalse(url, AbemaApprovalBrowserPolicy.allowedResource(url))
            assertFalse(url, AbemaApprovalBrowserPolicy.allowedNavigation(url))
        }
    }

    @Test fun unsafeAuthoritiesSchemesTraversalAndHiddenControlsAreRefused() {
        val rejected = listOf("", " https://abema.tv/", "http://abema.tv/", "javascript:alert(1)", "data:text/html,fixture",
            "file:///account", "content://abema.tv/account", "https:abema.tv/account", "https://ABEMA.tv/",
            "https://abema.tv./", "https://abema.tv:0443/", "https://abema.tv:444/", "https://abema.tv:/",
            "https://user@abema.tv/", "https://@abema.tv/", "https://abema.tv@evil.invalid/", "https://abema.tv.evil.invalid/",
            "https://other.abema.tv/", "https://auth.abema.tv.evil.invalid/", "https://127.0.0.1/",
            "https://abema.tv/a/../account", "https://abema.tv/a/./account", "https://abema.tv/a/%2e%2e/account",
            "https://abema.tv/a%2f..%2faccount", "https://abema.tv/\\account", "https://abema.tv/a?x=%5c",
            "https://abema.tv/a?x=%0a", "https://abema.tv/a#%00", "https://abema.tv/a?x=%ff",
            "https://abema.tv/a?x=%E2%80%AE", "https://abema.tv/a?x=%", "https://abema.tv/承認",
            "https://abema.tv/a?x=" + "a".repeat(8192))
        for (url in rejected) {
            assertFalse(url, AbemaApprovalBrowserPolicy.allowedNavigation(url))
            assertFalse(url, AbemaApprovalBrowserPolicy.allowedResource(url))
        }
    }

    @Test fun unrelatedOriginsRemainOutsideApprovalPolicy() {
        for (host in listOf("api.abema.io", "linear-abematv.akamaized.net",
            "id.twitch.tv", "gql.twitch.tv", "www.abema.tv", "auth.abema.tv.other.invalid")) {
            val url = "https://$host/fixture"
            assertFalse(url, AbemaApprovalBrowserPolicy.allowedNavigation(url))
            assertFalse(url, AbemaApprovalBrowserPolicy.allowedResource(url))
        }
    }
}
