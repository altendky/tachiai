package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbemaCachedHelperPolicyTest {
    @Test fun onlyExactOwnedPageAndThreeSubresourcesAreAdmitted() {
        assertTrue(AbemaCachedHelperPolicy.allows(URI(AbemaCachedHelperPolicy.PAGE), true, "GET"))
        listOf("control.js", "bundle.js", "initialize.js").forEach { name ->
            val uri = URI("${AbemaCachedHelperPolicy.ORIGIN}/runtime/$name")
            assertTrue(AbemaCachedHelperPolicy.allows(uri, false, "GET"))
            assertFalse(AbemaCachedHelperPolicy.allows(uri, true, "GET"))
            assertFalse(AbemaCachedHelperPolicy.allows(uri, false, "POST"))
        }
        assertFalse(AbemaCachedHelperPolicy.allows(URI(AbemaCachedHelperPolicy.PAGE), false, "GET"))
    }

    @Test fun providerAndOtherResourcesNeverInheritRuntimeAuthority() {
        listOf("https://abema.tv/", "https://tachiai-helper.invalid.evil/runtime/bundle.js",
            "https://tachiai-helper.invalid:443/runtime/bundle.js", "https://u@tachiai-helper.invalid/runtime/bundle.js",
            "https://tachiai-helper.invalid/runtime/bundle.js?token=x", "https://tachiai-helper.invalid/runtime/bundle.js#x",
            "https://tachiai-helper.invalid/runtime/../runtime/bundle.js", "file:///runtime/bundle.js",
            "https://tachiai-helper.invalid/runtime/unknown.js").forEach { value ->
            assertFalse(AbemaCachedHelperPolicy.allows(URI(value), false, "GET"))
        }
    }

    @Test fun cspAllowsNeitherInlineNorDynamicScriptsOrRemoteConnections() {
        val csp = AbemaCachedHelperPolicy.CONTENT_SECURITY_POLICY
        assertTrue(csp.contains("script-src 'self'"))
        assertTrue(csp.contains("connect-src 'none'"))
        assertFalse(csp.contains("unsafe-inline"))
        assertFalse(csp.contains("unsafe-eval"))
    }
}
