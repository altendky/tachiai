package net.fstab.tachiai.feature.diagnostic

import java.net.URI

internal const val BROWSER_LAB_URL = "https://appassets.androidplatform.net/assets/browser-lab/index.html"

// This inspectable application serves only its own credential-free fixture.
// No arbitrary URL extra, provider page, iframe, popup or account flow.
internal object BrowserLabPolicy {
    private val paths = setOf(
        "/assets/browser-lab/index.html",
        "/assets/browser-lab/lab.css",
        "/assets/browser-lab/lab.js",
    )

    fun allowsResource(uri: URI, method: String): Boolean = method == "GET" &&
        uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
        uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null &&
        uri.rawFragment == null && uri.rawPath in paths

    fun allowsNavigation(uri: URI): Boolean = uri.toString() == BROWSER_LAB_URL
}
