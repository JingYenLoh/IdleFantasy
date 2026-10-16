package com.fantasyidler.repository

import com.fantasyidler.data.json.SeasonalEventData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The seasonal events rotate forever off the calendar (the project is going into maintenance),
 * so every season boundary and year rollover must land on the right yearly run.
 */
class SeasonalCalendarTest {

    private val templates: Map<String, SeasonalEventData> by lazy {
        val file = listOf("src/main/assets/data/seasonal_events.json", "app/src/main/assets/data/seasonal_events.json")
            .map(::File)
            .firstOrNull(File::exists)
            ?: error("Could not locate seasonal_events.json from ${File(".").absolutePath}")
        Json { ignoreUnknownKeys = true }.decodeFromString(file.readText())
    }

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun activeId(nowMs: Long): String? = SeasonalCalendar.activeAt(templates, nowMs)?.id

    @Test
    fun `2026 runs keep their original ids and windows`() {
        // Players already hold tokens and banners under these exact ids.
        val gloom = SeasonalCalendar.activeAt(templates, utc(2026, 10, 16))!!
        assertEquals("gloomharvest_2026", gloom.id)
        assertEquals(1788220800000L, gloom.startMs)
        assertEquals(1796083200000L, gloom.endMs)

        val sun = SeasonalCalendar.byId(templates, "sunspire_solstice_2026")!!
        assertEquals(1780272000000L, sun.startMs)
        assertEquals(1788220800000L, sun.endMs)
    }

    @Test
    fun `each season boundary hands over to the next event`() {
        assertEquals("gloomharvest_2026", activeId(utc(2026, 11, 30, 23, 59)))
        assertEquals("frostfall_2026", activeId(utc(2026, 12, 1)))
        assertEquals("frostfall_2026", activeId(utc(2027, 2, 28, 23, 59)))
        assertEquals("bloomtide_2027", activeId(utc(2027, 3, 1)))
        assertEquals("bloomtide_2027", activeId(utc(2027, 5, 31, 23, 59)))
        assertEquals("sunspire_solstice_2027", activeId(utc(2027, 6, 1)))
        assertEquals("sunspire_solstice_2027", activeId(utc(2027, 8, 31, 23, 59)))
        assertEquals("gloomharvest_2027", activeId(utc(2027, 9, 1)))
    }

    @Test
    fun `winter keeps its start year across new year`() {
        assertEquals("frostfall_2027", activeId(utc(2027, 12, 31, 23, 59)))
        assertEquals("frostfall_2027", activeId(utc(2028, 1, 1)))
    }

    @Test
    fun `winter runs through a leap day`() {
        assertEquals("frostfall_2027", activeId(utc(2028, 2, 29, 12)))
        assertEquals("bloomtide_2028", activeId(utc(2028, 3, 1)))
        val frost = SeasonalCalendar.byId(templates, "frostfall_2027")!!
        assertEquals(utc(2028, 3, 1), frost.endMs)
    }

    @Test
    fun `an event never runs before its first year`() {
        // Bloomtide debuts in 2027; spring 2026 predates the rotation.
        assertNull(activeId(utc(2026, 4, 1)))
        assertTrue(SeasonalCalendar.startedRuns(templates, utc(2026, 10, 16)).none { it.series == "bloomtide" })
    }

    @Test
    fun `run ids round-trip and carry series and year`() {
        val run = SeasonalCalendar.byId(templates, "gloomharvest_2031")!!
        assertEquals("gloomharvest", run.series)
        assertEquals(2031, run.year)
        assertEquals("gloomharvest", SeasonalCalendar.seriesOf("gloomharvest_2031"))
        assertEquals(2031, SeasonalCalendar.yearOf("gloomharvest_2031"))
        assertNull(SeasonalCalendar.byId(templates, "removed_event_2026"))
    }

    @Test
    fun `started runs list every year so far and nothing in the future`() {
        val ids = SeasonalCalendar.startedRuns(templates, utc(2027, 7, 1)).map { it.id }.toSet()
        assertEquals(
            setOf("sunspire_solstice_2026", "gloomharvest_2026", "frostfall_2026", "bloomtide_2027", "sunspire_solstice_2027"),
            ids,
        )
    }

    @Test
    fun `every season has exactly one event`() {
        val seasons = templates.values.map { it.season }
        assertEquals(SeasonalCalendar.Season.entries.map { it.key }.toSet(), seasons.toSet())
        assertEquals(seasons.size, seasons.toSet().size)
    }
}
