package net.fstab.tachiai.platform.web

import java.net.URI
import net.fstab.tachiai.provider.isExactHttpsOrigin

internal object BrowserPopupPolicy {
    fun canOpen(hosts: Set<String>, userGesture: Boolean, alreadyOpen: Boolean) =
        hosts.isNotEmpty() && userGesture && !alreadyOpen

    fun allows(uri: URI, hosts: Set<String>) = hosts.any { uri.isExactHttpsOrigin(it) }

    // A newly created window can start blank. Never display that document: its
    // opener could write arbitrary content into it without an HTTPS navigation.
    fun isInitialBlank(uri: URI) = uri.toString() == "about:blank"

    fun classify(uri: URI?, hosts: Set<String>, alternateHosts: Set<String>): PopupDestinationClass = when {
        uri == null -> PopupDestinationClass.OTHER
        isInitialBlank(uri) -> PopupDestinationClass.INITIAL_BLANK
        allows(uri, hosts) -> PopupDestinationClass.ALLOWED_HTTPS
        allows(uri, alternateHosts) -> PopupDestinationClass.ALTERNATE_PROVIDER_HTTPS
        else -> PopupDestinationClass.OTHER
    }
}

internal class BrowserPopupNavigation(private val hosts: Set<String>) {
    private var visitedHttps = false
    private var target: URI? = null
    private var blocked = false

    fun start(uri: URI?): Boolean {
        target = null
        if (blocked || uri == null) { fail(); return false }
        if (BrowserPopupPolicy.isInitialBlank(uri) && !visitedHttps) return true
        if (!BrowserPopupPolicy.allows(uri, hosts)) { fail(); return false }
        visitedHttps = true
        target = uri
        return true
    }

    fun canDisplayCommitted(uri: URI?) = !blocked && uri != null && uri == target &&
        BrowserPopupPolicy.allows(uri, hosts)

    fun fail() { blocked = true; target = null }
}
