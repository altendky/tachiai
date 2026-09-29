package net.fstab.tachiai.platform.web

import net.fstab.tachiai.provider.BrowserIdentity

internal fun userAgentFor(identity: BrowserIdentity, defaultUserAgent: String): String = when (identity) {
    BrowserIdentity.DEFAULT -> defaultUserAgent
    BrowserIdentity.MOBILE_CHROME -> {
        if (!defaultUserAgent.contains("; wv") ||
            !defaultUserAgent.contains("Version/4.0 ") ||
            !defaultUserAgent.contains("Chrome/")
        ) {
            return defaultUserAgent
        }
        defaultUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }
    BrowserIdentity.DESKTOP_CHROME -> {
        val chromeVersion = Regex("Chrome/([^ ]+)")
            .find(defaultUserAgent)
            ?.groupValues
            ?.get(1)
            ?: return defaultUserAgent
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }
}
