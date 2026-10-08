package net.fstab.tachiai.platform.web

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagedAssetPolicyTest {
    private val paths = setOf("/assets/twitch/composite.html", "/assets/twitch/composite.js")

    @Test
    fun `composite serves only exact approved synthetic HTTPS paths`() {
        paths.forEach { path ->
            assertTrue(PackagedAssetPolicy.allows(URI("https://${PackagedAssetPolicy.HOST}$path?channel=test"), false, paths))
        }
        listOf(
            "http://appassets.androidplatform.net/assets/twitch/composite.html",
            "https://appassets.androidplatform.net:444/assets/twitch/composite.html",
            "https://user@appassets.androidplatform.net/assets/twitch/composite.html",
            "https://appassets.androidplatform.net.evil.example/assets/twitch/composite.html",
            "https://appassets.androidplatform.net/assets/twitch/player.html",
            "https://appassets.androidplatform.net/assets/twitch/%63omposite.html",
            "https://appassets.androidplatform.net/assets/twitch/../composite.html",
        ).forEach { url -> assertFalse(url, PackagedAssetPolicy.allows(URI(url), false, paths)) }
    }

    @Test
    fun `ordinary remote pages cannot read packaged assets by default`() {
        assertFalse(PackagedAssetPolicy.allows(URI("https://${PackagedAssetPolicy.HOST}/assets/twitch/composite.js"), false, emptySet()))
        assertTrue(PackagedAssetPolicy.allows(URI("https://${PackagedAssetPolicy.HOST}/assets/twitch/player.html"), true, emptySet()))
    }
}
