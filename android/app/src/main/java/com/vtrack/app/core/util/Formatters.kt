package com.vtrack.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Unit system; imperial is opt-in per profile. */
enum class UnitSystem { METRIC, IMPERIAL }

object Formatters {

    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val shortDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())
    private val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

    fun steps(value: Int): String = String.format(Locale.US, "%,d", value)

    fun compact(value: Long): String = when {
        abs(value) >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        abs(value) >= 10_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> String.format(Locale.US, "%,d", value)
    }

    /** Distance in km or mi, with one decimal below 100 and no decimals above. */
    fun distance(meters: Double, units: UnitSystem): String {
        val value = if (units == UnitSystem.METRIC) meters / 1000.0 else meters / 1609.344
        val suffix = if (units == UnitSystem.METRIC) "km" else "mi"
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, suffix)
        } else {
            String.format(Locale.US, "%.2f %s", value, suffix)
        }
    }

    fun distanceValue(meters: Double, units: UnitSystem): Double =
        if (units == UnitSystem.METRIC) meters / 1000.0 else meters / 1609.344

    fun distanceUnit(units: UnitSystem): String = if (units == UnitSystem.METRIC) "km" else "mi"

    fun elevation(meters: Double, units: UnitSystem): String = if (units == UnitSystem.METRIC) {
        "${meters.roundToInt()} m"
    } else {
        "${(meters * 3.28084).roundToInt()} ft"
    }

    /** mm:ss for <1h, h:mm:ss beyond. */
    fun duration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun shortDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun minutes(minutes: Int): String = "${minutes} min"

    /** Pace in min/km or min/mi. Returns "--" when the sample is not meaningful. */
    fun pace(secondsPerKm: Double, units: UnitSystem): String {
        if (secondsPerKm.isNaN() || secondsPerKm.isInfinite() || secondsPerKm <= 0 || secondsPerKm > 3600) {
            return "--"
        }
        val perUnit = if (units == UnitSystem.METRIC) secondsPerKm else secondsPerKm * 1.609344
        val min = perUnit.toInt() / 60
        val sec = perUnit.toInt() % 60
        return String.format(Locale.US, "%d:%02d /%s", min, sec, distanceUnit(units))
    }

    fun speed(secondsPerKm: Double, units: UnitSystem): String {
        if (secondsPerKm <= 0 || secondsPerKm.isNaN()) return "--"
        val kmh = 3600.0 / secondsPerKm
        return if (units == UnitSystem.METRIC) {
            String.format(Locale.US, "%.1f km/h", kmh)
        } else {
            String.format(Locale.US, "%.1f mph", kmh / 1.609344)
        }
    }

    fun calories(kcal: Double): String = String.format(Locale.US, "%.0f", kcal)

    fun weight(kg: Double, units: UnitSystem): String = if (units == UnitSystem.METRIC) {
        String.format(Locale.US, "%.1f kg", kg)
    } else {
        String.format(Locale.US, "%.1f lb", kg * 2.20462)
    }

    fun height(cm: Double, units: UnitSystem): String = if (units == UnitSystem.METRIC) {
        String.format(Locale.US, "%.0f cm", cm)
    } else {
        val totalIn = cm / 2.54
        String.format(Locale.US, "%d'%.0f\"", totalIn.toInt() / 12, totalIn % 12)
    }

    fun percent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100)

    fun dayLabel(date: LocalDate): String = date.format(dayFormatter)

    fun dayInitial(date: LocalDate): String = date.format(shortDayFormatter).uppercase(Locale.getDefault())

    fun monthLabel(date: LocalDate): String = date.format(monthFormatter)

    fun relativeDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> dayLabel(date)
        }
    }

    fun timeOfDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        return String.format(Locale.US, "%02d:%02d", local.hour, local.minute)
    }

    fun typed(value: Double, decimals: Int = 1): String = String.format(Locale.US, "%.${decimals}f", value)
}
