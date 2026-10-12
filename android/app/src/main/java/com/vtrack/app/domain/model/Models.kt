package com.vtrack.app.domain.model

import com.vtrack.app.core.util.UnitSystem
import java.time.LocalDate

/* ---------------------------------------------------------------- profile */

enum class FitnessLevel(val label: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced"),
    ;

    companion object {
        fun fromStorage(value: String?): FitnessLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BEGINNER
    }
}

enum class PrimaryGoal(val label: String, val blurb: String) {
    STAY_ACTIVE("Stay active", "Move more every day and build a lasting habit"),
    LOSE_WEIGHT("Manage weight", "Combine consistent activity with sensible intensity"),
    BUILD_STRENGTH("Build strength", "Progressive strength work 2–4 times a week"),
    IMPROVE_ENDURANCE("Improve endurance", "Gradually extend cardiovascular capacity"),
    RECOVER_MOBILITY("Mobility & recovery", "Move well, feel better, stay consistent"),
    ;

    companion object {
        fun fromStorage(value: String?): PrimaryGoal =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STAY_ACTIVE
    }
}

data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarEmoji: String?,
    val avatarUrl: String?,
    val birthYear: Int?,
    val sex: String?,
    val heightCm: Double?,
    val weightKg: Double?,
    val fitnessLevel: FitnessLevel,
    val primaryGoal: PrimaryGoal,
    val preferredActivities: List<ActivityType>,
    val weeklyWorkoutTarget: Int,
    val unitSystem: UnitSystem,
    val stepGoal: Int,
    val onboardingComplete: Boolean,
    val healthConnectEnabled: Boolean,
    val shareRoutes: Boolean,
    val shareActivityWithCoach: Boolean,
) {
    val age: Int?
        get() = birthYear?.let { LocalDate.now().year - it }

    /** Simple, documented BMI when both height and weight are known. */
    val bmi: Double?
        get() {
            val h = heightCm?.takeIf { it > 0 } ?: return null
            val w = weightKg ?: return null
            val meters = h / 100.0
            return w / (meters * meters)
        }

    companion object {
        fun empty(): UserProfile = UserProfile(
            id = "",
            email = "",
            displayName = "",
            avatarEmoji = null,
            avatarUrl = null,
            birthYear = null,
            sex = null,
            heightCm = null,
            weightKg = null,
            fitnessLevel = FitnessLevel.BEGINNER,
            primaryGoal = PrimaryGoal.STAY_ACTIVE,
            preferredActivities = listOf(ActivityType.WALKING),
            weeklyWorkoutTarget = 3,
            unitSystem = UnitSystem.METRIC,
            stepGoal = 8000,
            onboardingComplete = false,
            healthConnectEnabled = false,
            shareRoutes = false,
            shareActivityWithCoach = true,
        )
    }
}

/* ---------------------------------------------------------------- activity */

/** One local day of activity, as shown on the dashboard and charts. */
data class DailyActivity(
    val date: LocalDate,
    val steps: Int,
    val verifiedSteps: Int,
    val distanceMeters: Double,
    val calories: Double,
    val activeMinutes: Int,
    val goalSteps: Int,
    val firstSampleAt: Long?,
    val lastSampleAt: Long?,
) {
    val goalProgress: Float get() = if (goalSteps <= 0) 0f else steps.toFloat() / goalSteps
    val goalMet: Boolean get() = goalSteps > 0 && steps >= goalSteps

    companion object {
        fun empty(date: LocalDate, goalSteps: Int) = DailyActivity(
            date = date,
            steps = 0,
            verifiedSteps = 0,
            distanceMeters = 0.0,
            calories = 0.0,
            activeMinutes = 0,
            goalSteps = goalSteps,
            firstSampleAt = null,
            lastSampleAt = null,
        )
    }
}

/** Result of a live tracking session, used for summaries and sync. */
data class WorkoutSession(
    val id: String,
    val activityType: ActivityType,
    val title: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val calories: Double,
    val avgPaceSecPerKm: Double,
    val avgSpeedKmh: Double,
    val steps: Int,
    val elevationGainMeters: Double,
    val indoor: Boolean,
    val manual: Boolean,
    val autoTracked: Boolean,
    val notes: String?,
    val perceivedEffort: Int?,
    val syncState: SyncState,
) {
    val isFinished: Boolean get() = endedAt != null

    /** Clamped 0..1 progress against the daily active-minutes guideline (30 min default). */
    fun activeMinuteProgress(target: Int = 30): Float =
        (durationSeconds / 60f / target).coerceIn(0f, 1f)
}

enum class SyncState { PENDING, SYNCED, FAILED }

data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val recordedAt: Long,
)

/* ---------------------------------------------------------------- strength */

data class Exercise(
    val id: String,
    val name: String,
    val category: ActivityCategory,
    val primaryMuscle: String,
    val equipment: String,
    val difficulty: FitnessLevel,
    val metValue: Double,
    val instructions: List<String>,
    val cues: List<String>,
    val commonMistakes: List<String>,
    val defaultSets: Int?,
    val defaultReps: Int?,
    val defaultSeconds: Int?,
    val isCompound: Boolean,
    val isCustom: Boolean,
)

data class StrengthSet(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int?,
    val weightKg: Double?,
    val durationSeconds: Int?,
    val restSeconds: Int?,
    val rpe: Int?,
    val isWarmup: Boolean,
    val completedAt: Long,
) {
    /** Volume load in kg; bodyweight sets without added load report 0. */
    val volumeKg: Double get() = (weightKg ?: 0.0) * (reps ?: 0)
}

data class PersonalRecord(
    val exerciseId: String,
    val exerciseName: String,
    val bestWeightKg: Double?,
    val bestReps: Int?,
    val achievedAt: Long,
)

data class Routine(
    val id: String,
    val name: String,
    val description: String?,
    val activityType: ActivityType,
    val scheduledWeekdays: List<Int>,
    val items: List<RoutineItem>,
)

data class RoutineItem(
    val position: Int,
    val exerciseId: String?,
    val label: String,
    val sets: Int?,
    val reps: Int?,
    val durationSeconds: Int?,
    val restSeconds: Int?,
)

/* ---------------------------------------------------------------- rewards */

enum class CoinTransactionType(val label: String) {
    STEP_REWARD("Step reward"),
    WORKOUT_REWARD("Workout reward"),
    CHALLENGE_BONUS("Challenge bonus"),
    STREAK_BONUS("Streak bonus"),
    ACHIEVEMENT_BONUS("Achievement"),
    REDEMPTION("Redemption"),
    ADJUSTMENT("Adjustment"),
    ;

    companion object {
        fun fromStorage(value: String?): CoinTransactionType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ADJUSTMENT
    }
}

/**
 * Status of a coin entry. Only [CONFIRMED] entries (written by the server) count
 * toward the balance — local estimates exist purely to show an honest preview.
 */
enum class CoinStatus { LOCAL_ESTIMATE, PENDING, CONFIRMED, REJECTED }

data class CoinTransaction(
    val id: String,
    val clientEventId: String,
    val type: CoinTransactionType,
    val amount: Long,
    val reason: String,
    val activityRef: String?,
    val status: CoinStatus,
    val createdAt: Long,
    val rejectionReason: String? = null,
)

data class CoinBalance(
    val confirmed: Long,
    val pending: Long,
    val estimatedToday: Long = 0,
) {
    val totalVisible: Long get() = confirmed + pending
}

enum class ChallengeType(val label: String, val metricLabel: String) {
    DAILY_STEPS("Daily steps", "steps"),
    DAILY_DISTANCE("Daily distance", "km"),
    DAILY_ACTIVE_MINUTES("Active minutes", "min"),
    WORKOUT_COUNT("Workouts", "sessions"),
    WEEKLY_STEPS("Weekly steps", "steps"),
    STREAK_DAYS("Streak", "days"),
    SINGLE_WORKOUT_DISTANCE("Long workout", "km"),
    ;

    companion object {
        fun fromStorage(value: String?): ChallengeType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DAILY_STEPS
    }
}

enum class ChallengePeriod(val label: String) {
    DAILY("Today"),
    WEEKLY("This week"),
    ;

    companion object {
        fun fromStorage(value: String?): ChallengePeriod =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DAILY
    }
}

data class Challenge(
    val id: String,
    val code: String,
    val title: String,
    val description: String,
    val type: ChallengeType,
    val period: ChallengePeriod,
    val targetValue: Double,
    val rewardCoins: Long,
    val isActive: Boolean,
)

data class ChallengeProgress(
    val challenge: Challenge,
    val currentValue: Double,
    val completed: Boolean,
    val claimed: Boolean,
    val periodKey: String,
) {
    val fraction: Float
        get() = if (challenge.targetValue <= 0) 0f
        else (currentValue / challenge.targetValue).toFloat().coerceIn(0f, 1f)
}

data class Achievement(
    val id: String,
    val code: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: String,
    val threshold: Double,
    val progress: Double,
    val unlockedAt: Long?,
) {
    val unlocked: Boolean get() = unlockedAt != null
    val fraction: Float get() = if (threshold <= 0) 0f else (progress / threshold).toFloat().coerceIn(0f, 1f)
}

data class Reward(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val costCoins: Long,
    val emoji: String?,
    val stock: Int?,
    val fulfillmentNote: String?,
)

enum class RedemptionStatus(val label: String) {
    REQUESTED("Requested"),
    APPROVED("Approved"),
    FULFILLED("Fulfilled"),
    REJECTED("Rejected"),
    CANCELLED("Cancelled"),
    ;

    companion object {
        fun fromStorage(value: String?): RedemptionStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: REQUESTED
    }
}

data class Redemption(
    val id: String,
    val rewardId: String,
    val rewardTitle: String,
    val costCoins: Long,
    val status: RedemptionStatus,
    val note: String?,
    val requestedAt: Long,
)

/* ---------------------------------------------------------------- dashboard */

data class WeeklyBar(
    val date: LocalDate,
    val value: Float,
    val label: String,
    val isToday: Boolean,
)

/** Everything the home screen renders, assembled by a single use case. */
data class DashboardSnapshot(
    val profile: UserProfile,
    val today: DailyActivity,
    val week: List<DailyActivity>,
    val streakDays: Int,
    val longestStreak: Int,
    val balance: CoinBalance,
    val todayWorkouts: List<WorkoutSession>,
    val recommendedActivity: ActivityType,
    val coachHeadline: String,
    val coachMessage: String,
    val isOffline: Boolean,
)
