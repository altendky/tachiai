package net.fstab.tachiai.feature.presentation

import java.net.URI
import net.fstab.tachiai.presentation.PaneId
import net.fstab.tachiai.presentation.PaneSpec
import net.fstab.tachiai.presentation.PlaybackKind
import net.fstab.tachiai.presentation.Presentation
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.abema.AbemaAdapter
import net.fstab.tachiai.provider.twitch.TwitchAdapter

const val ABEMA_SUMO_TITLE_URL = "https://abema.tv/video/title/394-72"
const val ABEMA_SUMO_REPLAY_URL = "https://abema.tv/video/episode/394-72_s10_p8529"

fun createInitialSpikePresentation(twitchChannel: String = TwitchAdapter.CHANNEL) = Presentation(
    panes = listOf(
        abemaPane(ResourceLocator(ABEMA_SUMO_REPLAY_URL), PlaybackKind.REPLAY),
        twitchPane("commentary", TwitchAdapter.fullSiteResource(twitchChannel)),
    ),
)

fun createAbemaDiagnosticPresentation(url: String = ABEMA_SUMO_TITLE_URL): Presentation {
    val uri = runCatching { URI(url) }.getOrNull()
    require(uri != null && AbemaAdapter.isDiagnosticRouteAllowed(uri)) {
        "The ABEMA diagnostic must begin on an allowlisted playback route."
    }
    return Presentation(panes = listOf(abemaPane(ResourceLocator(url), PlaybackKind.UNKNOWN)))
}

fun createTwitchDiagnosticPresentation(
    twitchChannel: String,
    useFullSite: Boolean = false,
) = Presentation(
    panes = listOf(
        twitchPane(
            id = "commentary",
            resource = if (useFullSite) {
                TwitchAdapter.fullSiteResource(twitchChannel)
            } else {
                ResourceLocator(twitchChannel)
            },
        ),
    ),
)

fun createDualTwitchDiagnosticPresentation(
    primaryChannel: String,
    secondaryChannel: String,
    useFullSite: Boolean = false,
) = Presentation(
    panes = listOf(
        twitchPane(
            id = "twitch-primary",
            resource = if (useFullSite) {
                TwitchAdapter.fullSiteResource(primaryChannel)
            } else {
                ResourceLocator(primaryChannel)
            },
        ),
        twitchPane(
            id = "twitch-secondary",
            resource = if (useFullSite) {
                TwitchAdapter.fullSiteResource(secondaryChannel)
            } else {
                ResourceLocator(secondaryChannel)
            },
        ),
    ),
)

private fun abemaPane(resource: ResourceLocator, playbackKind: PlaybackKind) = PaneSpec(
    id = PaneId("primary-picture"),
    providerId = ProviderId("abema"),
    resource = resource,
    playbackKind = playbackKind,
    initiallyMuted = true,
)

private fun twitchPane(id: String, resource: ResourceLocator) = PaneSpec(
    id = PaneId(id),
    providerId = ProviderId("twitch"),
    resource = resource,
    playbackKind = PlaybackKind.LIVE,
    initiallyMuted = false,
)

val initialSpikePresentation = createInitialSpikePresentation()
