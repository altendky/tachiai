package net.fstab.tachiai.provider.abema

import java.net.URI
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.provider.isExactHttpsOrigin

object AbemaAdapter : ProviderAdapter {
    override val id = ProviderId("abema")
    override val displayName = "ABEMA"

    override fun browserRequest(resource: ResourceLocator): BrowserRequest {
        val uri = runCatching { URI(resource.value) }.getOrNull()
        require(uri != null && isTopLevelNavigationAllowed(uri)) {
            "The ABEMA resource must use an allowlisted HTTPS playback route."
        }
        return BrowserRequest(
            url = resource.value,
            browserIdentity = BrowserIdentity.DESKTOP_CHROME,
        )
    }

    override fun isTopLevelNavigationAllowed(uri: URI): Boolean =
        isProviderOrigin(uri) &&
            (uri.path.orEmpty().startsWith("/video/") || uri.path.orEmpty().startsWith("/channels/"))

    override fun isProtectedMediaOriginAllowed(uri: URI): Boolean =
        isProviderOrigin(uri)

    override fun isDiagnosticRouteAllowed(uri: URI): Boolean = isTopLevelNavigationAllowed(uri)

    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? {
        if (!isDiagnosticRouteAllowed(currentUri)) return null

        val operation = when (command) {
            BrowserCommand.PLAY -> "return media.play().then(() => 'ok').catch(() => 'blocked')"
            BrowserCommand.PAUSE -> "media.pause(); return 'paused'"
            BrowserCommand.MUTE -> "media.muted = true; return 'muted'"
            BrowserCommand.UNMUTE -> "media.muted = false; return 'unmuted'"
            BrowserCommand.VOLUME_DOWN -> """
                media.volume = Math.max(0, Math.round((media.volume - 0.1) * 10) / 10);
                return `volume ${'$'}{Math.round(media.volume * 100)}%`
            """.trimIndent()
            BrowserCommand.VOLUME_UP -> """
                media.volume = Math.min(1, Math.round((media.volume + 0.1) * 10) / 10);
                return `volume ${'$'}{Math.round(media.volume * 100)}%`
            """.trimIndent()
        }
        return """
            (() => {
              const media = Array.from(document.querySelectorAll('video'))
                .find((candidate) => candidate.offsetWidth > 0 && candidate.offsetHeight > 0);
              if (!media) return 'unavailable';
              $operation;
            })()
        """.trimIndent()
    }

    private fun isProviderOrigin(uri: URI): Boolean =
        uri.isExactHttpsOrigin("abema.tv") || uri.isExactHttpsOrigin("www.abema.tv")

}
