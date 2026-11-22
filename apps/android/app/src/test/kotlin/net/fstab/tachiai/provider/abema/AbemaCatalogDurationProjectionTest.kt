package net.fstab.tachiai.provider.abema

import org.junit.Assert.*
import org.junit.Test

class AbemaCatalogDurationProjectionTest {
    private val series = AbemaSeriesPageRequest("series-A")
    private fun page(items: String) = """{"seriesId":"series-A","seasonId":"series-A_s1","groups":[{"id":"series-A_eg1","name":"Group"}],""" +
        """"groupId":"series-A_eg1","viewingTypes":[1],"items":[$items],"more":false}"""
    private fun schedule(slots: String) = """{"slots":[$slots],"truncated":false}"""

    private val episodes = """{"kind":"episode","id":"episode-A","title":"Day one","durationSeconds":584},""" +
        """{"kind":"slot","id":"slot-A","channelId":"sumo","title":"Day one live","durationSeconds":17100},""" +
        """{"kind":"episode","id":"episode-B","title":"Day two"}"""

    @Test fun seriesEpisodesAndSlotsCarryAnOptionalBoundedDuration() {
        val decoded = decodeAbemaSeriesProjection(page(episodes), series)!!
        assertEquals(listOf(584L, 17100L, null), decoded.entries.map { it.durationSeconds })
        for (value in listOf("0", "-5", "1.5", "\"584\"", "null", "604801", "0584", "1e3", "9999999")) {
            assertNull(value, decodeAbemaSeriesProjection(page("""{"kind":"episode","id":"episode-A","title":"Day one","durationSeconds":$value}"""), series))
            assertNull(value, decodeAbemaSeriesProjection(page(
                """{"kind":"slot","id":"slot-A","channelId":"sumo","title":"Day one","durationSeconds":$value}"""), series))
        }
        assertNull(decodeAbemaSeriesProjection(page(
            """{"kind":"episode","id":"episode-A","title":"Day one","durationSeconds":1,"durationSeconds":2}"""), series))
        assertNull(decodeAbemaSeriesProjection(page("""{"kind":"episode","id":"episode-A","title":"Day one","length":584}"""), series))
        assertNull(decodeAbemaSeriesProjection(page("""{"kind":"episode","id":"episode-A","durationSeconds":584}"""), series))
    }

    @Test fun scheduleSlotsCarryAnOptionalBoundedDuration() {
        val decoded = decodeAbemaScheduleProjection(schedule(
            """{"id":"slot-A","channelId":"channel-A","title":"Day one","durationSeconds":1800},""" +
                """{"id":"slot-B","channelId":"channel-A","title":"Day two"}"""))!!
        assertEquals(listOf(1800L, null), decoded.entries.map { it.durationSeconds })
        for (value in listOf("0", "-1", "604801", "\"1800\"", "1800.0")) {
            assertNull(value, decodeAbemaScheduleProjection(schedule(
                """{"id":"slot-A","channelId":"channel-A","title":"Day one","durationSeconds":$value}""")))
        }
    }

    @Test fun durationsSurviveTheMetadataIpcProjections() {
        val page = decodeAbemaSeriesProjection(page(episodes), series)!!
        val crossed = decodeAbemaSeriesProjection(AbemaSeriesMetadataIpc.projection(page), series)!!
        assertEquals(listOf(584L, 17100L, null), crossed.entries.map { it.durationSeconds })
        val snapshot = decodeAbemaScheduleProjection(schedule(
            """{"id":"slot-A","channelId":"channel-A","title":"Day one","durationSeconds":1800},""" +
                """{"id":"slot-B","channelId":"channel-A","title":"Day two"}"""))!!
        val slots = decodeAbemaScheduleProjection(AbemaScheduleMetadataIpc.projection(snapshot))!!
        assertEquals(listOf(1800L, null), slots.entries.map { it.durationSeconds })
    }
}
