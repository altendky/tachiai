package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_REPLAY_URL

internal enum class AbemaInspectionSource(val url: String) {
    NEWS("https://abema.tv/now-on-air/abema-news"),
    REPLAY(ABEMA_SUMO_REPLAY_URL),
}

internal object AbemaInspectionPolicy {
    const val SOURCE_EXTRA = "net.fstab.tachiai.extra.ABEMA_INSPECTION_SOURCE"
    const val PROFILE_SUFFIX = "abema-inspection"

    fun source(value: String?): AbemaInspectionSource? = when (value) {
        null, "NEWS" -> AbemaInspectionSource.NEWS
        "REPLAY" -> AbemaInspectionSource.REPLAY
        else -> null
    }

    // A separate WebView profile requires API 28. Never fall back to the account profile.
    fun supportsInspection(apiLevel: Int): Boolean = apiLevel >= 28

    fun allowsNavigation(uri: URI): Boolean = AbemaInspectionSource.entries.any { uri.toString() == it.url }
}
