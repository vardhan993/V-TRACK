package com.vtrack.app.core.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * All activity is bucketed by *local* calendar day. The server stores the same
 * `YYYY-MM-DD` day key plus the zone offset so reports never drift across DST or travel.
 */
object TimeUtils {

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** Stable day key, e.g. "2026-10-12". */
    fun dayKey(epochMillis: Long, zone: ZoneId = zone()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    fun dayKey(date: LocalDate): String = date.toString()

    fun todayKey(zone: ZoneId = zone()): String = LocalDate.now(zone).toString()

    fun parseDayKey(key: String): LocalDate = LocalDate.parse(key)

    fun startOfDay(epochMillis: Long, zone: ZoneId = zone()): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayExclusive(epochMillis: Long, zone: ZoneId = zone()): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Hour bucket (0-23) used to store step samples compactly. */
    fun hourOfDay(epochMillis: Long, zone: ZoneId = zone()): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    /**
     * Milliseconds of "today elapsed" — used to distinguish genuinely low activity
     * from a day that has only just begun (rewards and coaching depend on this).
     */
    fun dayProgress(nowMillis: Long, zone: ZoneId = zone()): Float {
        val dayStart = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val elapsed = (nowMillis - dayStart).toFloat()
        return (elapsed / (24f * 60 * 60 * 1000)).coerceIn(0f, 1f)
    }

    fun lastNDays(n: Int, zone: ZoneId = zone()): List<LocalDate> {
        val today = LocalDate.now(zone)
        return (n - 1 downTo 0).map { today.minusDays(it.toLong()) }
    }

    /** ISO week (Monday-first), which is what the weekly chart and streak display use. */
    fun currentWeek(zone: ZoneId = zone()): List<LocalDate> {
        val today = LocalDate.now(zone)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L until 7L).map { monday.plusDays(it) }
    }

    fun currentMonth(zone: ZoneId = zone()): YearMonth = YearMonth.now(zone)

    fun monthsOfYear(year: Int): List<YearMonth> = (1..12).map { YearMonth.of(year, it) }

    /**
     * Longest run of consecutive days ending today (or yesterday) that satisfy
     * [qualifies]. `today` may be in progress and does not break a streak.
     */
    fun currentStreak(
        days: List<String>,
        qualifies: (String) -> Boolean,
        zone: ZoneId = zone(),
    ): Int {
        val keys = days.toHashSet()
        var streak = 0
        var cursor = LocalDate.now(zone)
        // A day that is still in progress must not reset an otherwise healthy streak.
        if (!qualifies(todayKey(zone))) {
            cursor = cursor.minusDays(1)
        }
        while (keys.contains(cursor.toString()) && qualifies(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** Longest streak ever recorded in the supplied history. */
    fun longestStreak(days: List<String>, qualifies: (String) -> Boolean): Int {
        val sorted = days.filter(qualifies).sorted()
        if (sorted.isEmpty()) return 0
        var best = 1
        var run = 1
        var previous = parseDayKey(sorted.first())
        for (i in 1 until sorted.size) {
            val current = parseDayKey(sorted[i])
            run = if (current == previous.plusDays(1)) run + 1 else 1
            if (run > best) best = run
            previous = current
        }
        return best
    }

    fun isSameDay(a: Long, b: Long, zone: ZoneId = zone()): Boolean =
        dayKey(a, zone) == dayKey(b, zone)
}
