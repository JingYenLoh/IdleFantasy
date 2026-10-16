package com.fantasyidler.repository

import com.fantasyidler.data.json.SeasonalEventData
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Turns the yearly event templates in seasonal_events.json into dated runs.
 *
 * Each template names a meteorological [Season]; a run of it is "<series>_<year>", spanning
 * that season in UTC. Winter takes the year it starts in (frostfall_2026 = Dec 2026 to Feb
 * 2027). Every per-run piece of player state (tokens, claims, market limits, banners, titles)
 * is keyed on the run id, so each year starts a fresh track with no extra bookkeeping.
 */
object SeasonalCalendar {

    enum class Season(val key: String, val startMonth: Int) {
        SPRING("spring", 3),
        SUMMER("summer", 6),
        AUTUMN("autumn", 9),
        WINTER("winter", 12);

        companion object {
            fun fromKey(key: String): Season? = entries.firstOrNull { it.key == key }
        }
    }

    private val YEAR_SUFFIX = Regex("_(\\d{4})$")

    /** "gloomharvest_2027" -> "gloomharvest". Ids without a year suffix are returned unchanged. */
    fun seriesOf(eventId: String): String = eventId.replace(YEAR_SUFFIX, "")

    /** "gloomharvest_2027" -> 2027, or null when the id carries no year suffix. */
    fun yearOf(eventId: String): Int? = YEAR_SUFFIX.find(eventId)?.groupValues?.get(1)?.toInt()

    /** Start (inclusive) and end (exclusive) epoch ms of [season] in [year], in UTC. */
    fun window(season: Season, year: Int): Pair<Long, Long> {
        val start = LocalDate.of(year, season.startMonth, 1)
        val end = start.plusMonths(3)
        return start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
            end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /** The season running at [nowMs] and the year its run is named after. */
    fun seasonAt(nowMs: Long): Pair<Season, Int> {
        val date = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        return when (date.monthValue) {
            in 3..5  -> Season.SPRING to date.year
            in 6..8  -> Season.SUMMER to date.year
            in 9..11 -> Season.AUTUMN to date.year
            12       -> Season.WINTER to date.year
            else     -> Season.WINTER to date.year - 1
        }
    }

    /** The dated run of [template] for [year], or null if the template's season is unknown. */
    fun instance(template: SeasonalEventData, year: Int): SeasonalEventData? {
        val season = Season.fromKey(template.season) ?: return null
        val (start, end) = window(season, year)
        return template.copy(
            id      = "${template.id}_$year",
            series  = template.id,
            year    = year,
            startMs = start,
            endMs   = end,
        )
    }

    /** The run active at [nowMs], or null when no template covers the current season. */
    fun activeAt(templates: Map<String, SeasonalEventData>, nowMs: Long): SeasonalEventData? {
        val (season, year) = seasonAt(nowMs)
        val template = templates.values.firstOrNull { it.season == season.key && year >= it.firstYear }
            ?: return null
        return instance(template, year)
    }

    /** Resolves a run id like "gloomharvest_2027" back to its dated run, or null if unknown. */
    fun byId(templates: Map<String, SeasonalEventData>, eventId: String): SeasonalEventData? {
        val template = templates[seriesOf(eventId)] ?: return null
        val year = yearOf(eventId) ?: return null
        return instance(template, year)
    }

    /** Every run that has started by [nowMs], from each template's first year onward. */
    fun startedRuns(templates: Map<String, SeasonalEventData>, nowMs: Long): List<SeasonalEventData> {
        val currentYear = seasonAt(nowMs).second
        return templates.values.flatMap { template ->
            (template.firstYear..currentYear + 1).mapNotNull { year ->
                instance(template, year)?.takeIf { it.startMs <= nowMs }
            }
        }
    }
}
