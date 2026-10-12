package com.vtrack.app.domain.model

/**
 * Activity catalogue. [met] is the Metabolic Equivalent of Task used by the calorie
 * estimator (Compendium of Physical Activities style values for a moderate effort
 * level); it is an estimate, not a measurement, and the UI says so.
 */
enum class ActivityType(
    val label: String,
    val met: Double,
    val tracksDistance: Boolean,
    val usesGps: Boolean,
    val countsSteps: Boolean,
    val category: ActivityCategory,
    val emoji: String,
) {
    WALKING("Walk", 3.5, true, true, true, ActivityCategory.CARDIO, "🚶"),
    RUNNING("Run", 9.8, true, true, true, ActivityCategory.CARDIO, "🏃"),
    CYCLING("Cycle", 7.5, true, true, false, ActivityCategory.CARDIO, "🚴"),
    HIKING("Hike", 6.0, true, true, true, ActivityCategory.CARDIO, "🥾"),
    TREADMILL("Treadmill", 8.0, true, false, true, ActivityCategory.CARDIO, "🏃‍♂️"),
    ELLIPTICAL("Elliptical", 5.0, false, false, false, ActivityCategory.CARDIO, "🛞"),
    ROWING("Rowing", 7.0, true, false, false, ActivityCategory.CARDIO, "🚣"),
    SWIMMING("Swim", 7.0, true, false, false, ActivityCategory.CARDIO, "🏊"),
    STAIR_CLIMBING("Stair climb", 8.8, false, false, true, ActivityCategory.CARDIO, "🪜"),
    STRENGTH("Strength", 5.0, false, false, false, ActivityCategory.STRENGTH, "🏋️"),
    BODYWEIGHT("Bodyweight", 4.5, false, false, false, ActivityCategory.STRENGTH, "🤸"),
    HIIT("HIIT", 8.5, false, false, false, ActivityCategory.STRENGTH, "⚡"),
    YOGA("Yoga", 2.5, false, false, false, ActivityCategory.MOBILITY, "🧘"),
    STRETCHING("Stretching", 2.3, false, false, false, ActivityCategory.MOBILITY, "🤍"),
    MOBILITY("Mobility", 2.8, false, false, false, ActivityCategory.MOBILITY, "🔄"),
    DANCE("Dance", 5.5, false, false, false, ActivityCategory.FUN, "💃"),
    SPORT("Sport", 6.5, true, true, true, ActivityCategory.FUN, "⚽"),
    OTHER("Other", 4.0, false, false, false, ActivityCategory.OTHER, "✨"),
    ;

    val isStrength: Boolean get() = category == ActivityCategory.STRENGTH

    val needsLocationPermission: Boolean get() = usesGps

    companion object {
        fun fromStorage(value: String?): ActivityType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER

        /** Quick-start tiles shown on the dashboard, in the order users pick them most. */
        val quickStart: List<ActivityType> = listOf(WALKING, RUNNING, CYCLING, STRENGTH, HIIT, YOGA)

        val cardio: List<ActivityType> = entries.filter { it.category == ActivityCategory.CARDIO }
        val strengthAndBodyweight: List<ActivityType> =
            listOf(STRENGTH, BODYWEIGHT, HIIT)
        val mobility: List<ActivityType> = entries.filter { it.category == ActivityCategory.MOBILITY }
        val funActivities: List<ActivityType> = entries.filter { it.category == ActivityCategory.FUN }
    }
}

enum class ActivityCategory(val label: String) {
    CARDIO("Cardio"),
    STRENGTH("Strength"),
    MOBILITY("Mobility"),
    FUN("Fun & sport"),
    OTHER("Other"),
}
