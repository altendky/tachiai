package net.fstab.tachiai.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PresentationTest {
    @Test
    fun `presentation preserves an ordered provider-neutral pane list`() {
        val first = pane("first", "provider-a")
        val second = pane("second", "provider-b")

        val presentation = Presentation(listOf(first, second))

        assertEquals(listOf(first, second), presentation.panes)
    }

    @Test
    fun `presentation rejects duplicate pane identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            Presentation(listOf(pane("same", "one"), pane("same", "two")))
        }
    }

    private fun pane(id: String, provider: String) = PaneSpec(
        id = PaneId(id),
        providerId = ProviderId(provider),
        resource = ResourceLocator("opaque-resource"),
        playbackKind = PlaybackKind.UNKNOWN,
        initiallyMuted = false,
    )
}
