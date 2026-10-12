package com.vtrack.app.domain.coach

import com.vtrack.app.domain.model.ActivityCategory
import com.vtrack.app.domain.model.WorkoutSession
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Pure, deterministic metrics. No clock access, no randomness. */
object CoachMetricsCalculator {

    fun compute(context: CoachContext): CoachMetrics {
        val days = context.recentDays.sortedBy { it.date }
        val last7 = days.filter { it.date > context.today.minusDays(7) }
        val last28 = days.filter { it.date > context.today.minusDays(28) }
        val previous7 = days.filter {
            it.date <= context.today.minusDays(7) && it.date > context.today.minusDays(14)
        }

        val workouts = context.recentWorkouts.filter { it.isFinished }
        val workouts7 = workouts.filter { dayOf(it) > context.today.minusDays(7) }

        val minutesByCategory = workouts7
            .groupBy { it.activityType.category }
            .mapValues { (_, list) -> list.sumOf { (it.durationSeconds / 60).toInt() } }
            .toMutableMap()
        // Passive step movement contributes to the "cardio" bucket as light activity.
        val passiveCardio = last7.sumOf { it.activeMinutes } - (minutesByCategory[ActivityCategory.CARDIO] ?: 0)
        if (passiveCardio > 0) {
            minutesByCategory[ActivityCategory.CARDIO] =
                (minutesByCategory[ActivityCategory.CARDIO] ?: 0) + passiveCardio.coerceAtLeast(0)
        }

        val lastWorkoutDay = workouts.maxByOrNull { it.endedAt ?: it.startedAt }?.let { dayOf(it) }
        val daysSinceLastWorkout = lastWorkoutDay?.let { ChronoUnit.DAYS.between(it, context.today).toInt() }

        val avg7 = last7.map { it.steps }.average().roundToInt().takeIf { last7.isNotEmpty() } ?: 0
        val avg28 = last28.map { it.steps }.average().roundToInt().takeIf { last28.isNotEmpty() } ?: 0
        val prevAvg = previous7.map { it.steps }.average()
        val trend = if (previous7.isNotEmpty() && prevAvg > 0) {
            (avg7 - prevAvg) / prevAvg
        } else {
            0.0
        }

        val avgSessionMinutes = if (workouts.isEmpty()) 0 else {
            (workouts.take(10).sumOf { it.durationSeconds } / 60.0 / workouts.take(10).size).roundToInt()
        }

        return CoachMetrics(
            daysWithData = days.size,
            averageSteps7 = avg7,
            averageSteps28 = avg28,
            averageActiveMinutes7 = if (last7.isEmpty()) 0 else last7.sumOf { it.activeMinutes } / last7.size,
            activeMinutesLast7 = last7.sumOf { it.activeMinutes },
            workoutsLast7 = workouts7.size,
            minutesByCategory7 = minutesByCategory,
            daysSinceLastWorkout = daysSinceLastWorkout,
            consecutiveTrainingDays = consecutiveTrainingDays(workouts, context.today),
            averageSessionMinutes = avgSessionMinutes,
            stepGoalHitDays7 = last7.count { it.goalMet },
            stepGoalHitDays28 = last28.count { it.goalMet },
            trendStepsWeekOverWeek = trend,
        )
    }

    /** Training days in a row ending today or yesterday — drives recovery scheduling. */
    fun consecutiveTrainingDays(workouts: List<WorkoutSession>, today: LocalDate): Int {
        val days = workouts.map { dayOf(it) }.toSortedSet(compareByDescending { it })
        if (days.isEmpty()) return 0
        var cursor = today
        if (!days.contains(cursor)) {
            cursor = cursor.minusDays(1)
            if (!days.contains(cursor)) return 0
        }
        var count = 0
        while (days.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    fun dayOf(session: WorkoutSession): LocalDate =
        java.time.Instant.ofEpochMilli(session.startedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()

    /** Number of rest/recovery days implied by the guideline of ≤6 hard days per week. */
    fun recommendedRecoveryDays(level: com.vtrack.app.domain.model.FitnessLevel): Int = when (level) {
        com.vtrack.app.domain.model.FitnessLevel.BEGINNER -> 3
        com.vtrack.app.domain.model.FitnessLevel.INTERMEDIATE -> 2
        com.vtrack.app.domain.model.FitnessLevel.ADVANCED -> 1
    }
}
