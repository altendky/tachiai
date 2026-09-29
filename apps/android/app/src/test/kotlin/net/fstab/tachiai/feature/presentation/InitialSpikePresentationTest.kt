package net.fstab.tachiai.feature.presentation

import net.fstab.tachiai.presentation.PlaybackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialSpikePresentationTest {
    @Test
    fun `initial spike pairs an ABEMA replay with live Twitch commentary`() {
        assertEquals(listOf("abema", "twitch"), initialSpikePresentation.panes.map { it.providerId.value })
        assertEquals(2, initialSpikePresentation.panes.size)
        assertEquals(PlaybackKind.REPLAY, initialSpikePresentation.panes[0].playbackKind)
        assertEquals(PlaybackKind.LIVE, initialSpikePresentation.panes[1].playbackKind)
        assertEquals("https://m.twitch.tv/midnightsumo", initialSpikePresentation.panes[1].resource.value)
    }

    @Test
    fun `diagnostic presentation can override only the Twitch channel`() {
        val presentation = createInitialSpikePresentation("queenbee")

        assertEquals("https://abema.tv/video/episode/394-72_s10_p8529", presentation.panes[0].resource.value)
        assertEquals("https://m.twitch.tv/queenbee", presentation.panes[1].resource.value)
    }

    @Test
    fun `single-pane Twitch diagnostic preserves the generic presentation model`() {
        val presentation = createTwitchDiagnosticPresentation("queenbee")

        assertEquals(1, presentation.panes.size)
        assertEquals("twitch", presentation.panes.single().providerId.value)
        assertEquals("queenbee", presentation.panes.single().resource.value)
    }

    @Test
    fun `single-pane ABEMA diagnostic preserves the supplied public resource`() {
        val url = "https://abema.tv/video/episode/394-72_s10_p176"
        val presentation = createAbemaDiagnosticPresentation(url)

        assertEquals(1, presentation.panes.size)
        assertEquals("abema", presentation.panes.single().providerId.value)
        assertEquals(url, presentation.panes.single().resource.value)
        assertTrue(presentation.panes.single().initiallyMuted)
    }

    @Test
    fun `single-pane ABEMA diagnostic rejects a non-playback route`() {
        assertThrows(IllegalArgumentException::class.java) {
            createAbemaDiagnosticPresentation("https://abema.tv/account")
        }
    }

    @Test
    fun `single-pane diagnostic can use the Twitch full site`() {
        val presentation = createTwitchDiagnosticPresentation("queenbee", useFullSite = true)

        assertEquals("https://m.twitch.tv/queenbee", presentation.panes.single().resource.value)
    }

    @Test
    fun `dual Twitch diagnostic keeps two independent generic panes`() {
        val presentation = createDualTwitchDiagnosticPresentation(
            primaryChannel = "queenbee",
            secondaryChannel = "midnightsumo",
            useFullSite = true,
        )

        assertEquals(listOf("twitch-primary", "twitch-secondary"), presentation.panes.map { it.id.value })
        assertEquals(
            listOf("https://m.twitch.tv/queenbee", "https://m.twitch.tv/midnightsumo"),
            presentation.panes.map { it.resource.value },
        )
        assertTrue(presentation.panes.all { it.providerId.value == "twitch" })
    }
}
