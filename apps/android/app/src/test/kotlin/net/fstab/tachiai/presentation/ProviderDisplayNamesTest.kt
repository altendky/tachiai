package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class ProviderDisplayNamesTest {
    @Test fun providerSubstringSuppressesTheSeparateProviderWhilePreservingTheName() {
        for (name in listOf("ABEMA", "abema News", "Weekend ABEMA", "ABEMASports")) {
            val displayed = providerDisplayNames(name, "ABEMA")
            assertEquals(name, displayed.primary)
            assertNull(displayed.provider)
            assertEquals(name, displayed.inline)
        }
        assertEquals("My tWiTcH", providerDisplayNames("My tWiTcH", "Twitch").inline)
    }

    @Test fun blankAndDistinctNamesHaveNoEmptySubtitlesOrSeparators() {
        for (name in listOf("", " ", "\t\n")) {
            assertEquals(ProviderDisplayNames("ABEMA"), providerDisplayNames(name, "ABEMA"))
            assertEquals("ABEMA", providerDisplayNames(name, "ABEMA").inline)
        }
        assertEquals(ProviderDisplayNames("Sumo", "ABEMA"), providerDisplayNames("Sumo", "ABEMA"))
        assertEquals("Sumo · ABEMA", providerDisplayNames("Sumo", "ABEMA").inline)
    }

    @Test fun presentationKeepsStoredNameAndInstanceIdentityUnchanged() {
        val original = defaultProviderInstances().first().copy(customName = "aBeMa Night")
        assertEquals("aBeMa Night", providerDisplayNames(original).inline)
        assertEquals("aBeMa Night", original.customName)
        assertEquals(defaultProviderInstances().first().id, original.id)
        assertEquals("ABEMA 2", providerDisplayNames(original.copy(customName = null, defaultName = "ABEMA 2")).inline)
    }
}
