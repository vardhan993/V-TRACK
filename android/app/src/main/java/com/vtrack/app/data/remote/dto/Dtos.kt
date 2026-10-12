package com.vtrack.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the V-Track Workers API. JSON is camelCase on both sides so the
 * mapping stays obvious; unknown fields are ignored so a newer server never breaks an
 * older app (and vice-versa).
 */

/* ------------------------------------------------------------------ auth */

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val user: UserDto,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarEmoji: String? = null,
    val avatarUrl: String? = null,
    val birthYear: Int? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val fitnessLevel: String = "BEGINNER",
    val primaryGoal: String = "STAY_ACTIVE",
    val preferredActivities: List<String> = emptyList(),
    val weeklyWorkoutTarget: Int = 3,
    val unitSystem: String = "METRIC",
    val stepGoal: Int = 8000,
    val onboardingComplete: Boolean = false,
    val healthConnectEnabled: Boolean = false,
    val shareRoutes: Boolean = false,
    val shareActivityWithCoach: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val avatarEmoji: String? = null,
    val birthYear: Int? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val fitnessLevel: String? = null,
    val primaryGoal: String? = null,
    val preferredActivities: List<String>? = null,
    val weeklyWorkoutTarget: Int? = null,
    val unitSystem: String? = null,
    val stepGoal: Int? = null,
    val onboardingComplete: Boolean? = null,
    val healthConnectEnabled: Boolean? = null,
    val shareRoutes: Boolean? = null,
    val shareActivityWithCoach: Boolean? = null,
)

/* --------------------------------------------------------------- activity */

@Serializable
data class StepBucketDto(
    val hour: Int,
    val steps: Int,
    val distanceMeters: Double,
    val calories: Double,
    val activeSeconds: Int,
)

@Serializable
data class ActivityDayUpload(
    val dayKey: String,
    val steps: Int,
    val distanceMeters: Double,
    val calories: Double,
    val activeMinutes: Int,
    val source: String,
    val zoneOffsetMinutes: Int,
    val buckets: List<StepBucketDto> = emptyList(),
)

@Serializable
data class ActivitySyncRequest(val days: List<ActivityDayUpload>)

@Serializable
data class ActivitySyncResponse(
    val acceptedDays: Int,
    val verifiedSteps: Map<String, Int>,
    val rejected: List<String> = emptyList(),
)

@Serializable
data class ActivityDayDto(
    val dayKey: String,
    val steps: Int,
    val verifiedSteps: Int,
    val distanceMeters: Double,
    val calories: Double,
    val activeMinutes: Int,
    val updatedAt: Long,
)

@Serializable
data class ClaimStepsRequest(
    val clientEventId: String,
    val dayKey: String,
    val steps: Int,
    val activeSeconds: Int,
    val source: String,
    val zoneOffsetMinutes: Int,
)

@Serializable
data class ClaimWorkoutRequest(
    val clientEventId: String,
    val workoutClientId: String,
)

/* --------------------------------------------------------------- workouts */

@Serializable
data class WorkoutUpload(
    val clientId: String,
    val activityType: String,
    val title: String? = null,
    val startedAt: Long,
    val endedAt: Long,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val calories: Double,
    val avgPaceSecPerKm: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val steps: Int = 0,
    val elevationGainMeters: Double = 0.0,
    val indoor: Boolean = false,
    val manual: Boolean = false,
    val notes: String? = null,
    val perceivedEffort: Int? = null,
    val sets: List<StrengthSetUpload> = emptyList(),
)

@Serializable
data class StrengthSetUpload(
    val clientId: String,
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val durationSeconds: Int? = null,
    val restSeconds: Int? = null,
    val rpe: Int? = null,
    val isWarmup: Boolean = false,
    val completedAt: Long,
)

@Serializable
data class WorkoutDto(
    val id: String,
    val clientId: String? = null,
    val activityType: String,
    val title: String? = null,
    val startedAt: Long,
    val endedAt: Long,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val calories: Double,
    val steps: Int,
    val verified: Boolean = false,
    val coinsAwarded: Long = 0,
    val notes: String? = null,
    val perceivedEffort: Int? = null,
    val createdAt: Long = 0,
)

@Serializable
data class WorkoutSyncResponse(
    val accepted: Int,
    val duplicates: Int,
    val workouts: List<WorkoutDto> = emptyList(),
)

@Serializable
data class UpdateWorkoutRequest(val notes: String? = null, val perceivedEffort: Int? = null)

/* ---------------------------------------------------------------- rewards */

@Serializable
data class CoinClaimResponse(
    val transactionId: String,
    val clientEventId: String,
    val amount: Long,
    val status: String,
    val balance: Long,
    val reason: String,
    val duplicate: Boolean = false,
)

@Serializable
data class BalanceDto(
    val confirmed: Long,
    val pending: Long,
    val earnedToday: Long,
    val dailyStepCap: Long,
    val remainingToday: Long,
    val lifetimeEarned: Long,
    val engineVersion: String,
)

@Serializable
data class CoinTransactionDto(
    val id: String,
    val clientEventId: String,
    val type: String,
    val amount: Long,
    val reason: String,
    val activityRef: String? = null,
    val status: String,
    val balanceAfter: Long? = null,
    val createdAt: Long,
)

@Serializable
data class TransactionPageDto(
    val items: List<CoinTransactionDto>,
    val nextCursor: String? = null,
)

@Serializable
data class ChallengeDto(
    val id: String,
    val code: String,
    val title: String,
    val description: String,
    val type: String,
    val period: String,
    val targetValue: Double,
    val rewardCoins: Long,
    val isActive: Boolean,
    val currentValue: Double = 0.0,
    val completed: Boolean = false,
    val claimed: Boolean = false,
    val periodKey: String,
)

@Serializable
data class ChallengeListDto(val items: List<ChallengeDto>, val periodKey: String)

@Serializable
data class AchievementDto(
    val id: String,
    val code: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: String,
    val threshold: Double,
    val progress: Double,
    val unlockedAt: Long? = null,
)

@Serializable
data class RewardDto(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val costCoins: Long,
    val emoji: String? = null,
    val stock: Int? = null,
    val fulfillmentNote: String? = null,
    val isActive: Boolean = true,
)

@Serializable
data class RedemptionRequest(val note: String? = null)

@Serializable
data class RedemptionDto(
    val id: String,
    val rewardId: String,
    val rewardTitle: String,
    val costCoins: Long,
    val status: String,
    val note: String? = null,
    val requestedAt: Long,
)

/* ------------------------------------------------------------------ coach */

@Serializable
data class CoachDayDto(
    val dayKey: String,
    val title: String,
    val detail: String,
    val activityType: String? = null,
    val targetMinutes: Int = 0,
    val targetSteps: Int = 0,
    val isRecovery: Boolean = false,
    val isRest: Boolean = false,
    val rationale: String = "",
)

@Serializable
data class CoachPlanDto(
    val id: String,
    val weekStartDayKey: String,
    val headline: String,
    val summary: String,
    val dailyStepTarget: Int,
    val weeklyActiveMinutesTarget: Int,
    val weeklyWorkoutTarget: Int,
    val engineVersion: String,
    val generatedBy: String,
    val progressions: List<String> = emptyList(),
    val safetyNotes: List<String> = emptyList(),
    val days: List<CoachDayDto> = emptyList(),
)

@Serializable
data class CoachAskRequest(val question: String)

@Serializable
data class CoachAnswerDto(
    val topic: String,
    val answer: String,
    val bulletPoints: List<String> = emptyList(),
    val safetyRedirect: Boolean = false,
    val disclaimer: String,
)

@Serializable
data class CoachReviewDto(
    val weekStartDayKey: String,
    val activeMinutes: Int,
    val activeMinutesTarget: Int,
    val workoutsCompleted: Int,
    val workoutsTarget: Int,
    val averageSteps: Int,
    val consistencyScore: Int,
    val highlights: List<String> = emptyList(),
    val watchOuts: List<String> = emptyList(),
    val nextWeekFocus: String = "",
    val recoveryRecommendation: String = "",
    val adjustmentDirection: String = "HOLD",
    val adjustmentPercent: Int = 0,
    val adjustmentReason: String = "",
)

/* ----------------------------------------------------------------- config */

@Serializable
data class ServerConfigDto(
    val coinEngineVersion: String,
    val coinsPerStep: Double,
    val maxStepCoinsPerDay: Long,
    val minStepsForReward: Int,
    val maxCreditedStepsPerDay: Int,
    val coinMinClaimGapSeconds: Long,
    val generativeAiEnabled: Boolean = false,
    val aiProvider: String? = null,
    val minSupportedAppVersion: Int = 1,
    val serverTime: Long = 0L,
)

/* ---------------------------------------------------------------- export */

@Serializable
data class ExportDto(
    val exportedAt: Long,
    val profile: UserDto,
    val activity: List<ActivityDayDto> = emptyList(),
    val workouts: List<WorkoutDto> = emptyList(),
    val transactions: List<CoinTransactionDto> = emptyList(),
)

/* ----------------------------------------------------------------- errors */

@Serializable
data class ApiErrorDto(val error: ApiErrorBodyDto? = null)

@Serializable
data class ApiErrorBodyDto(
    val code: String = "error",
    val message: String = "Request failed",
    @SerialName("retryAfter") val retryAfter: Long? = null,
)
