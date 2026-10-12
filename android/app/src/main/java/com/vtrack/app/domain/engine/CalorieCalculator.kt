package com.vtrack.app.domain.engine

import com.vtrack.app.domain.model.ActivityType

/**
 * Every number this class produces is an *estimate* derived from published formulas plus
 * the profile the user supplied (weight/height). The UI labels these values as estimates.
 *
 * - MET formula: kcal/min = MET × 3.5 × kg / 200  (standard ACSM equation)
 * - Step formula: kcal = distance_km × kg × 0.53 for walking, × 1.036 for running
 *   (rough energy cost of locomotion; falls back to 0.53 when the gait is unknown)
 *
 * With no body weight on file we use a neutral 70 kg reference and say so in the UI.
 */
object CalorieCalculator {

    const val REFERENCE_WEIGHT_KG = 70.0
    private const val WALKING_STRIDE_RATIO = 0.415
    private const val RUNNING_STRIDE_RATIO = 0.473
    private const val FALLBACK_STRIDE_METERS = 0.72
    private const val WALKING_KCAL_FACTOR = 0.53
    private const val RUNNING_KCAL_FACTOR = 1.036

    fun effectiveWeight(weightKg: Double?): Double =
        weightKg?.takeIf { it in 25.0..300.0 } ?: REFERENCE_WEIGHT_KG

    fun isEstimatedWeight(weightKg: Double?): Boolean = weightKg == null || weightKg !in 25.0..300.0

    /** Energy for a session of known MET and duration. */
    fun fromMet(met: Double, weightKg: Double, durationSeconds: Long): Double {
        if (durationSeconds <= 0) return 0.0
        val minutes = durationSeconds / 60.0
        return met * 3.5 * weightKg / 200.0 * minutes
    }

    fun fromActivity(activity: ActivityType, weightKg: Double, durationSeconds: Long): Double =
        fromMet(activity.met, weightKg, durationSeconds)

    /** Stride length in metres, from height when available. */
    fun strideMeters(heightCm: Double?, activity: ActivityType): Double {
        val ratio = if (activity == ActivityType.RUNNING) RUNNING_STRIDE_RATIO else WALKING_STRIDE_RATIO
        val height = heightCm?.takeIf { it in 100.0..250.0 } ?: return FALLBACK_STRIDE_METERS
        return height * ratio / 100.0
    }

    /** Distance implied by a step count — used when GPS is unavailable (indoor walking). */
    fun distanceFromSteps(steps: Int, heightCm: Double?, activity: ActivityType): Double {
        if (steps <= 0) return 0.0
        return steps * strideMeters(heightCm, activity)
    }

    /**
     * Calorie estimate from steps alone. Prefer [fromActivity] when the session duration
     * and type are known: duration-based MET is the more defensible estimate, and this
     * method exists for passive all-day step counting where no session exists.
     */
    fun fromSteps(
        steps: Int,
        weightKg: Double?,
        heightCm: Double?,
        activity: ActivityType = ActivityType.WALKING,
    ): Double {
        if (steps <= 0) return 0.0
        val kg = effectiveWeight(weightKg)
        val km = distanceFromSteps(steps, heightCm, activity) / 1000.0
        val factor = if (activity == ActivityType.RUNNING) RUNNING_KCAL_FACTOR else WALKING_KCAL_FACTOR
        return km * kg * factor
    }

    /**
     * A pragmatic blend for passive daily tracking: steps give the distance-based estimate
     * and we never let the daily total fall below plain basal activity for the elapsed day.
     */
    fun dailyEstimate(
        steps: Int,
        weightKg: Double?,
        heightCm: Double?,
        activeSeconds: Int,
        heightRatio: Double = 1.0,
    ): Double {
        val fromStepsValue = fromSteps(steps, weightKg, heightCm, ActivityType.WALKING)
        val kg = effectiveWeight(weightKg)
        val activeMinutes = activeSeconds / 60.0
        val metBased = fromMet(3.5, kg, (activeMinutes * 60).toLong()) * heightRatio
        return maxOf(fromStepsValue, metBased * 0.6)
    }

    /** "Moderate" minutes require at least ~100 steps/min for walking (WHO-ish heuristic). */
    fun countsAsActiveMinute(stepsInMinute: Int): Boolean = stepsInMinute >= 100

    fun formatKcal(kcal: Double): String = when {
        kcal <= 0 -> "0"
        kcal < 100 -> String.format(java.util.Locale.US, "%.0f", kcal)
        else -> String.format(java.util.Locale.US, "%.0f", kcal)
    }
}
