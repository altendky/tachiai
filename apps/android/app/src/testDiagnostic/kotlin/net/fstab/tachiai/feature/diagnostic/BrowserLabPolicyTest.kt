package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLabPolicyTest {
    @Test fun `only packaged fixture resources are served`() {
        assertTrue(BrowserLabPolicy.allowsResource(URI(BROWSER_LAB_URL), "GET"))
        assertTrue(BrowserLabPolicy.allowsResource(URI(BROWSER_LAB_URL.replace("index.html", "lab.js")), "GET"))
        assertTrue(BrowserLabPolicy.allowsResource(URI(BROWSER_LAB_URL.replace("index.html", "lab.css")), "GET"))
        assertFalse(BrowserLabPolicy.allowsResource(URI(BROWSER_LAB_URL), "POST"))
        assertFalse(BrowserLabPolicy.allowsResource(URI("https://www.twitch.tv/login"), "GET"))
        assertFalse(BrowserLabPolicy.allowsResource(URI(BROWSER_LAB_URL.replace("browser-lab/index.html", "twitch/session.html")), "GET"))
    }

    @Test fun `resource authority and spelling must match exactly`() {
        listOf(
            BROWSER_LAB_URL.replace("https:", "http:"),
            BROWSER_LAB_URL.replace(".net/", ".net:443/"),
            BROWSER_LAB_URL.replace("https://", "https://user@"),
            "$BROWSER_LAB_URL?token=not-real",
            "$BROWSER_LAB_URL#fragment",
            BROWSER_LAB_URL.replace("index.html", "../browser-lab/index.html"),
            BROWSER_LAB_URL.replace("index.html", "%69ndex.html"),
        ).forEach { assertFalse(it, BrowserLabPolicy.allowsResource(URI(it), "GET")) }
    }

    @Test fun `navigation cannot open provider or other packaged pages`() {
        assertTrue(BrowserLabPolicy.allowsNavigation(URI(BROWSER_LAB_URL)))
        assertFalse(BrowserLabPolicy.allowsNavigation(URI("https://www.twitch.tv/bobross")))
        assertFalse(BrowserLabPolicy.allowsNavigation(URI("about:blank")))
        assertFalse(BrowserLabPolicy.allowsNavigation(URI(BROWSER_LAB_URL.replace("index.html", "lab.js"))))
    }
}
