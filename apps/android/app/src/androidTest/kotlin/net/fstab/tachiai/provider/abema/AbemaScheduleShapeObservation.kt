package net.fstab.tachiai.provider.abema

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test

// Test APK only. This marker never changes the production metadata contract.
internal object AbemaScheduleShapeObservation {
    const val MARKER = "tachiaiTestScheduleShape"
    const val ANCHOR = "return Object.freeze({ slots, truncated: reply.slots.length > slots.length });"
    private const val MAX_ROWS = 20_000
    private const val MAX_OFFSET = 3_660_000
    private const val MARKER_BYTES = 8192
    val timeFields = listOf("startAt", "endAt", "timeshiftEndAt", "timeshiftFreeEndAt")
    val timeCategories = listOf("ABSENT", "NULL", "ZERO", "POSITIVE_SECONDS", "INVALID")
    val flagFields = listOf("timeshift", "timeshiftFree")
    val flagCategories = listOf("PARENT_UNAVAILABLE", "ABSENT", "NULL", "TRUE", "FALSE", "INVALID")
    val intervalCategories = listOf("UNAVAILABLE", "INVALID_ORDER", "ENDED", "FUTURE", "CONTAINS_DEVICE_CLOCK")

    val TEST_SCHEDULE_PROJECTION = """
        const tachiaiShape = (() => {
          const clock = __TACHIAI_TEST_SCHEDULE_NOW_MS__;
          if (!Number.isSafeInteger(clock) || clock < 0 || clock > 8640000000000000) throw new Error();
          const record = value => !!value && typeof value === 'object' && !Array.isArray(value);
          const timeFields = ['startAt', 'endAt', 'timeshiftEndAt', 'timeshiftFreeEndAt'];
          const timeCategories = ['ABSENT', 'NULL', 'ZERO', 'POSITIVE_SECONDS', 'INVALID'];
          const flagFields = ['timeshift', 'timeshiftFree'];
          const flagCategories = ['PARENT_UNAVAILABLE', 'ABSENT', 'NULL', 'TRUE', 'FALSE', 'INVALID'];
          const intervalCategories = ['UNAVAILABLE', 'INVALID_ORDER', 'ENDED', 'FUTURE', 'CONTAINS_DEVICE_CLOCK'];
          const zero = categories => Object.fromEntries(categories.map(category => [category, 0]));
          const timestamp = (value, field) => {
            if (!Object.hasOwn(value, field)) return 'ABSENT';
            const item = value[field];
            if (item === null) return 'NULL';
            if (item === 0) return 'ZERO';
            // Local diagnostic bound through Gregorian year9999, not provider admission.
            return Number.isSafeInteger(item) && item > 0 && item <= 253402300799 ? 'POSITIVE_SECONDS' : 'INVALID';
          };
          const flag = (value, field) => {
            if (!record(value)) return 'PARENT_UNAVAILABLE';
            if (!Object.hasOwn(value, field)) return 'ABSENT';
            return value[field] === null ? 'NULL' : value[field] === true ? 'TRUE' : value[field] === false ? 'FALSE' : 'INVALID';
          };
          const aggregate = rows => {
            const counts = { rows: rows.length, ...Object.fromEntries(timeFields.map(field => [field, zero(timeCategories)])),
              ...Object.fromEntries(flagFields.map(field => [field, zero(flagCategories)])),
              interval: zero(intervalCategories), endedExplicitFree: 0 };
            for (const row of rows) {
              for (const field of timeFields) counts[field][timestamp(row, field)]++;
              for (const field of flagFields) counts[field][flag(row.flags, field)]++;
              let interval = 'UNAVAILABLE';
              if (timestamp(row, 'startAt') === 'POSITIVE_SECONDS' && timestamp(row, 'endAt') === 'POSITIVE_SECONDS') {
                interval = row.endAt <= row.startAt ? 'INVALID_ORDER' : row.endAt * 1000 <= clock ? 'ENDED' :
                  row.startAt * 1000 > clock ? 'FUTURE' : 'CONTAINS_DEVICE_CLOCK';
              }
              counts.interval[interval]++;
              if (interval === 'ENDED' && flag(row.flags, 'timeshiftFree') === 'TRUE') counts.endedExplicitFree++;
            }
            return counts;
          };
          // setUTCFullYear preserves Gregorian years 0000..0099; Date.UTC does not.
          const day = (year, month, date) => {
            const value = new Date(0); value.setUTCHours(0, 0, 0, 0); value.setUTCFullYear(year, month - 1, date);
            if (value.getUTCFullYear() !== year || value.getUTCMonth() !== month - 1 || value.getUTCDate() !== date)
              throw new Error();
            return value.getTime() / 86400000;
          };
          const tokyo = new Date(clock + 9 * 3600000);
          const today = day(tokyo.getUTCFullYear(), tokyo.getUTCMonth() + 1, tokyo.getUTCDate());
          const horizon = { past: 0, today: 0, future: 0, minOffset: null, maxOffset: null };
          for (const date of reply.availableDates) {
            const offset = day(Number(date.slice(0, 4)), Number(date.slice(4, 6)), Number(date.slice(6))) - today;
            if (!Number.isSafeInteger(offset) || Math.abs(offset) > 3660000) throw new Error();
            horizon[offset < 0 ? 'past' : offset > 0 ? 'future' : 'today']++;
            horizon.minOffset = horizon.minOffset === null ? offset : Math.min(horizon.minOffset, offset);
            horizon.maxOffset = horizon.maxOffset === null ? offset : Math.max(horizon.maxOffset, offset);
          }
          return { version: 1, rawRows: reply.slots.length, projectedRows: slots.length,
            displayedRows: Math.min(slots.length, 500), channelCount: reply.channels.length,
            dateCount: reply.availableDates.length, truncated: reply.slots.length > slots.length,
            displayTruncated: reply.slots.length > 500,
            all: aggregate(reply.slots), projected: aggregate(reply.slots.slice(0, slots.length)),
            displayed: aggregate(reply.slots.slice(0, 500)), horizon };
        })();
        return Object.freeze({ slots, truncated: reply.slots.length > slots.length,
          tachiaiTestScheduleShape: Object.freeze(tachiaiShape) });
    """.trimIndent()

    fun projection(wallMs: Long): String {
        require(wallMs in 0..8_640_000_000_000_000L)
        return TEST_SCHEDULE_PROJECTION.replace("__TACHIAI_TEST_SCHEDULE_NOW_MS__", wallMs.toString())
    }

    class Rows(val count: Int, val groups: Map<String, Map<String, Int>>, val endedExplicitFree: Int) {
        fun render() = "rows=$count endedExplicitFree=$endedExplicitFree " + groups.entries.joinToString(" ") { (field, categories) ->
            categories.entries.joinToString(" ") { (category, count) -> "$field$category=$count" }
        }
    }
    class Counts(val rawRows: Int, val projectedRows: Int, val displayedRows: Int, val channels: Int, val dates: Int,
        val truncated: Boolean, val displayTruncated: Boolean, val all: Rows, val projected: Rows, val displayed: Rows,
        val past: Int, val today: Int, val future: Int, val minOffset: Int?, val maxOffset: Int?) {
        fun header() = "rawRows=$rawRows projectedRows=$projectedRows displayedRows=$displayedRows channels=$channels dates=$dates " +
            "truncated=$truncated displayTruncated=$displayTruncated datePast=$past dateToday=$today dateFuture=$future " +
            "minDayOffset=${minOffset ?: "NONE"} maxDayOffset=${maxOffset ?: "NONE"}"
    }
    class Stripped(val raw: String, val counts: Counts)

    private val groups = timeFields.associateWith { timeCategories } + flagFields.associateWith { flagCategories } +
        mapOf("interval" to intervalCategories)
    private fun keys(value: JSONObject, expected: Set<String>) = check(value.keys().asSequence().toSet() == expected)
    private fun integer(value: Any?, bound: IntRange): Int {
        val number = when (value) { is Int -> value.toLong(); is Long -> value; else -> error("Count refused") }
        check(number in bound.first.toLong()..bound.last.toLong()); return number.toInt()
    }
    private fun count(value: JSONObject, field: String) = integer(value.opt(field), 0..MAX_ROWS)
    private fun rows(value: JSONObject, expected: Int): Rows {
        keys(value, groups.keys + setOf("rows", "endedExplicitFree"))
        check(count(value, "rows") == expected)
        val counts = groups.mapValues { (field, categories) ->
            val group = value.opt(field) as? JSONObject ?: error("Group refused")
            keys(group, categories.toSet())
            categories.associateWith { count(group, it) }.also { check(it.values.sum() == expected) }
        }
        val ended = count(value, "endedExplicitFree")
        check(ended <= counts.getValue("interval").getValue("ENDED") && ended <= counts.getValue("timeshiftFree").getValue("TRUE"))
        check(ended >= maxOf(0, counts.getValue("interval").getValue("ENDED") + counts.getValue("timeshiftFree").getValue("TRUE") - expected))
        val validStart = counts.getValue("startAt").getValue("POSITIVE_SECONDS")
        val validEnd = counts.getValue("endAt").getValue("POSITIVE_SECONDS")
        val available = expected - counts.getValue("interval").getValue("UNAVAILABLE")
        check(available <= minOf(validStart, validEnd) && available >= maxOf(0, validStart + validEnd - expected))
        check(counts.getValue("timeshift").getValue("PARENT_UNAVAILABLE") == counts.getValue("timeshiftFree").getValue("PARENT_UNAVAILABLE"))
        return Rows(expected, counts, ended)
    }
    private fun subset(prefix: Rows, full: Rows) {
        check(prefix.count <= full.count && prefix.endedExplicitFree <= full.endedExplicitFree &&
            full.endedExplicitFree - prefix.endedExplicitFree <= full.count - prefix.count)
        for ((field, categories) in prefix.groups) for ((category, count) in categories)
            check(count <= full.groups.getValue(field).getValue(category))
    }

    fun strip(raw: String?): Stripped? = runCatching {
        val maximum = ABEMA_SCHEDULE_PROJECTION_BYTES + MARKER_BYTES
        val encoded = raw?.takeIf { it.length <= maximum * 2 } ?: return null
        checkAbemaCollectionJson(encoded)
        val outer = JSONTokener(encoded); val text = outer.nextValue() as? String ?: return null
        check(outer.nextClean() == '\u0000' && text.toByteArray(Charsets.UTF_8).size <= maximum)
        checkAbemaCollectionJson(text)
        val inner = JSONTokener(text); val value = inner.nextValue() as? JSONObject ?: return null
        check(inner.nextClean() == '\u0000'); keys(value, setOf("slots", "truncated", MARKER))
        val marker = value.remove(MARKER) as? JSONObject ?: return null
        check(marker.toString().toByteArray(Charsets.UTF_8).size <= MARKER_BYTES)
        keys(marker, setOf("version", "rawRows", "projectedRows", "displayedRows", "channelCount", "dateCount",
            "truncated", "displayTruncated", "all", "projected", "displayed", "horizon"))
        check(integer(marker.opt("version"), 1..1) == 1)
        val total = count(marker, "rawRows"); val projected = count(marker, "projectedRows"); val displayed = count(marker, "displayedRows")
        check(projected == minOf(total, 501) && displayed == minOf(projected, 500))
        val truncated = marker.opt("truncated") as? Boolean ?: return null
        val displayTruncated = marker.opt("displayTruncated") as? Boolean ?: return null
        check(truncated == (total > projected) && displayTruncated == (total > 500) && value.opt("truncated") == truncated)
        val all = rows(marker.opt("all") as? JSONObject ?: return null, total)
        val prefix = rows(marker.opt("projected") as? JSONObject ?: return null, projected)
        val visible = rows(marker.opt("displayed") as? JSONObject ?: return null, displayed)
        subset(prefix, all); subset(visible, prefix)
        val channels = count(marker, "channelCount"); val dates = count(marker, "dateCount")
        val horizon = marker.opt("horizon") as? JSONObject ?: return null
        keys(horizon, setOf("past", "today", "future", "minOffset", "maxOffset"))
        val past = count(horizon, "past"); val today = count(horizon, "today"); val future = count(horizon, "future")
        check(past + today + future == dates && today <= 1)
        fun offset(field: String): Int? = if (horizon.opt(field) === JSONObject.NULL) null else integer(horizon.opt(field), -MAX_OFFSET..MAX_OFFSET)
        val low = offset("minOffset"); val high = offset("maxOffset")
        if (dates == 0) check(low == null && high == null) else {
            check(low != null && high != null && low <= high)
            check((past > 0) == (low < 0) && (future > 0) == (high > 0))
            check(today == 0 || low <= 0 && high >= 0)
            check(dates.toLong() <= high.toLong() - low.toLong() + 1)
            check(dates != 1 || low == high)
            check(low != 0 && high != 0 || today == 1)
            check(past.toLong() <= if (low < 0) minOf(high, -1).toLong() - low + 1 else 0)
            check(future.toLong() <= if (high > 0) high.toLong() - maxOf(low, 1) + 1 else 0)
        }
        val stripped = value.toString()
        val snapshot = decodeAbemaScheduleProjection(stripped) ?: return null
        check(snapshot.entries.size == projected && snapshot.truncated == truncated)
        Stripped(JSONObject.quote(stripped), Counts(total, projected, displayed, channels, dates, truncated, displayTruncated,
            all, prefix, visible, past, today, future, low, high))
    }.getOrNull()
}

class AbemaScheduleShapeObservationTest {
    private fun rows(count: Int): JSONObject = JSONObject().put("rows", count).put("endedExplicitFree", 0).apply {
        AbemaScheduleShapeObservation.timeFields.forEach { field -> put(field, JSONObject().apply {
            AbemaScheduleShapeObservation.timeCategories.forEach { put(it, if (it == "ABSENT") count else 0) }
        }) }
        AbemaScheduleShapeObservation.flagFields.forEach { field -> put(field, JSONObject().apply {
            AbemaScheduleShapeObservation.flagCategories.forEach { put(it, if (it == "PARENT_UNAVAILABLE") count else 0) }
        }) }
        put("interval", JSONObject().apply { AbemaScheduleShapeObservation.intervalCategories.forEach { put(it, if (it == "UNAVAILABLE") count else 0) } })
    }
    private fun projection(total: Int): JSONObject {
        val projected = minOf(total, 501); val displayed = minOf(total, 500)
        val slots = org.json.JSONArray().apply { repeat(projected) { put(JSONObject().put("id", "fixture-$it").put("channelId", "fixture-channel").put("title", "Fixture")) } }
        val marker = JSONObject().put("version", 1).put("rawRows", total).put("projectedRows", projected).put("displayedRows", displayed)
            .put("channelCount", 1).put("dateCount", 0).put("truncated", total > projected).put("displayTruncated", total > 500)
            .put("all", rows(total)).put("projected", rows(projected)).put("displayed", rows(displayed))
            .put("horizon", JSONObject().put("past", 0).put("today", 0).put("future", 0).put("minOffset", JSONObject.NULL).put("maxOffset", JSONObject.NULL))
        return JSONObject().put("slots", slots).put("truncated", total > projected).put(AbemaScheduleShapeObservation.MARKER, marker)
    }
    private fun strip(value: JSONObject) = AbemaScheduleShapeObservation.strip(JSONObject.quote(value.toString()))

    @Test fun closedMarkerStripsBeforeNormalScheduleDecodingAtDisplayAndPrefixBoundaries() {
        for (count in listOf(0, 1, 499, 500, 501, 502, 20_000)) {
            val result = checkNotNull(strip(projection(count)))
            assertEquals(count, result.counts.rawRows)
            val decoded = checkNotNull(decodeAbemaScheduleProjection(JSONTokener(result.raw).nextValue() as String))
            assertEquals(minOf(count, 501), decoded.entries.size); assertEquals(count > 501, decoded.truncated)
            assertFalse(result.raw.contains(AbemaScheduleShapeObservation.MARKER))
        }
    }

    @Test fun markerRejectsUnknownFieldsFractionalCountsDuplicatesAndIncoherentTotals() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("extra", true) }, { it.put("version", 2) }, { it.put("rawRows", 1.5) },
            { it.put("projectedRows", 0) }, { it.put("displayTruncated", true) }, { it.put("rawRows", 20_001) },
            { it.getJSONObject("all").getJSONObject("startAt").put("UNVERIFIED", 1) },
            { it.getJSONObject("all").getJSONObject("startAt").put("ABSENT", 0) },
            { it.getJSONObject("all").put("endedExplicitFree", 1) },
            { it.getJSONObject("all").getJSONObject("interval").put("UNAVAILABLE", 0).put("ENDED", 1) },
            { it.getJSONObject("horizon").put("minOffset", 0) }, { it.getJSONObject("horizon").put("today", 1) },
        )
        for (mutate in mutations) { val value = projection(1); mutate(value.getJSONObject(AbemaScheduleShapeObservation.MARKER)); assertNull(strip(value)) }
        val valid = projection(1).toString()
        for (text in listOf(valid + " trailing", valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"tachiaiTestScheduleShape\":", "\"tachiaiTestScheduleShape\":null,\"tachiaiTestScheduleShape\":"),
            valid.replace("\"tachiaiTestScheduleShape\":", "\"tachiaiTestScheduleShape\":null,\"tachiaiTestScheduleSh\\u0061pe\":")))
            assertNull(AbemaScheduleShapeObservation.strip(JSONObject.quote(text)))
    }

    @Test fun markerRequiresMonotonePrefixCategoriesAndValidDistinctDateHorizon() {
        val value = projection(502); val marker = value.getJSONObject(AbemaScheduleShapeObservation.MARKER)
        marker.getJSONObject("all").getJSONObject("timeshiftFree").put("PARENT_UNAVAILABLE", 501).put("TRUE", 1)
        marker.getJSONObject("all").getJSONObject("timeshift").put("PARENT_UNAVAILABLE", 501).put("ABSENT", 1)
        assertNotNull(strip(value))
        marker.getJSONObject("displayed").getJSONObject("timeshiftFree").put("PARENT_UNAVAILABLE", 499).put("TRUE", 1)
        marker.getJSONObject("displayed").getJSONObject("timeshift").put("PARENT_UNAVAILABLE", 499).put("ABSENT", 1)
        assertNull(strip(value))
        fun horizon(past: Int, today: Int, future: Int, low: Any, high: Any) = projection(0).apply {
            getJSONObject(AbemaScheduleShapeObservation.MARKER).put("dateCount", past + today + future)
                .put("horizon", JSONObject().put("past", past).put("today", today).put("future", future).put("minOffset", low).put("maxOffset", high))
        }
        assertNotNull(strip(horizon(1, 1, 1, -1, 1)))
        for (bad in listOf(horizon(1, 0, 0, 0, 0), horizon(0, 0, 1, 1, 0), horizon(0, 2, 0, 0, 0),
            horizon(3, 0, 0, -1, -1), horizon(0, 0, 1, 1, JSONObject.NULL), horizon(0, 0, 1, 1, 3_660_001))) assertNull(strip(bad))
    }

    @Test fun admittedDiagnosticIntervalsAndInvalidTimesKeepMetadataUnknownWithoutScheduleOrEntitlement() {
        for (interval in AbemaScheduleShapeObservation.intervalCategories) {
            val value = projection(1); val marker = value.getJSONObject(AbemaScheduleShapeObservation.MARKER)
            for (scope in listOf("all", "projected", "displayed")) {
                val counts = marker.getJSONObject(scope)
                counts.getJSONObject("startAt").put("ABSENT", 0).put("POSITIVE_SECONDS", 1)
                counts.getJSONObject("endAt").put("ABSENT", 0).put(if (interval == "UNAVAILABLE") "INVALID" else "POSITIVE_SECONDS", 1)
                counts.getJSONObject("interval").put("UNAVAILABLE", 0).put(interval, 1)
                counts.getJSONObject("timeshift").put("PARENT_UNAVAILABLE", 0).put("ABSENT", 1)
                counts.getJSONObject("timeshiftFree").put("PARENT_UNAVAILABLE", 0).put("TRUE", 1)
                counts.put("endedExplicitFree", if (interval == "ENDED") 1 else 0)
            }
            val result = checkNotNull(strip(value))
            val snapshot = checkNotNull(decodeAbemaScheduleProjection(JSONTokener(result.raw).nextValue() as String))
            assertEquals(net.fstab.tachiai.provider.catalog.CatalogAvailability.UNKNOWN, snapshot.entries.single().availability)
            assertNull(snapshot.entries.single().scheduledStartEpochMs)
            if (interval == "ENDED") {
                marker.getJSONObject("all").put("endedExplicitFree", 0)
                assertNull(strip(value))
            }
        }
    }
}
