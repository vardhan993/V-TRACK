package com.vtrack.app.domain.coach

import com.vtrack.app.domain.model.ActivityCategory
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.DailyActivity
import com.vtrack.app.domain.model.PrimaryGoal
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.domain.model.WorkoutSession
import java.time.LocalDate

/** Everything the coaching engine is allowed to look at. Passed in, never fetched. */
data class CoachContext(
    val profile: UserProfile,
    val today: LocalDate,
    /** Oldest → newest, up to the last 28 local days. Missing days are simply absent. */
    val recentDays: List<DailyActivity>,
    /** Newest first, up to the last ~30 finished sessions. */
    val recentWorkouts: List<WorkoutSession>,
    val streakDays: Int,
    val bestStreak: Int,
    val coinsEarnedLast7Days: Long = 0L,
    val personalRecords: List<String> = emptyList(),
)

/** Where a plan came from — shown in the UI so nothing is misrepresented. */
enum class CoachSource(val label: String) {
    RULES_LOCAL("V-Track rules engine (on device)"),
    RULES_SERVER("V-Track rules engine (server)"),
    GENERATIVE_MODEL("Optional generative model"),
}

data class CoachPrescription(
    val day: LocalDate,
    val weekdayLabel: String,
    val title: String,
    val detail: String,
    val activityType: ActivityType?,
    val focus: ActivityCategory,
    val targetMinutes: Int,
    val targetSteps: Int,
    val isRecovery: Boolean,
    val isRest: Boolean,
    val rationale: String,
)

data class CoachPlan(
    val id: String,
    val weekStart: LocalDate,
    val goal: PrimaryGoal,
    val headline: String,
    val summary: String,
    val prescriptions: List<CoachPrescription>,
    val dailyStepTarget: Int,
    val weeklyActiveMinutesTarget: Int,
    val weeklyWorkoutTarget: Int,
    val engineVersion: String,
    val generatedBy: CoachSource,
    val progressions: List<String>,
    val safetyNotes: List<String>,
)

data class CoachBriefing(
    val headline: String,
    val message: String,
    val suggestedActivity: ActivityType,
    val stepTargetToday: Int,
    val encouragement: String,
    val suggestedMinutes: Int,
    val isRecoveryDay: Boolean,
)

data class PlanAdjustment(val direction: Direction, val percent: Int, val reason: String) {
    enum class Direction { INCREASE, HOLD, REDUCE }
}

data class WeeklyReview(
    val weekStart: LocalDate,
    val activeMinutes: Int,
    val activeMinutesTarget: Int,
    val workoutsCompleted: Int,
    val workoutsTarget: Int,
    val averageSteps: Int,
    val stepTarget: Int,
    val consistencyScore: Int,
    val highlights: List<String>,
    val watchOuts: List<String>,
    val nextWeekFocus: String,
    val recoveryRecommendation: String,
    val adjustment: PlanAdjustment,
    val muscleGroupBalance: Map<String, Int>,
)

data class CoachAnswer(
    val question: String,
    val topic: String,
    val answer: String,
    val bulletPoints: List<String>,
    val safetyRedirect: Boolean,
    val disclaimer: String,
)

/** Derived metrics. Pure functions of [CoachContext] so results are reproducible. */
data class CoachMetrics(
    val daysWithData: Int,
    val averageSteps7: Int,
    val averageSteps28: Int,
    val averageActiveMinutes7: Int,
    val activeMinutesLast7: Int,
    val workoutsLast7: Int,
    val minutesByCategory7: Map<ActivityCategory, Int>,
    val daysSinceLastWorkout: Int?,
    val consecutiveTrainingDays: Int,
    val averageSessionMinutes: Int,
    val stepGoalHitDays7: Int,
    val stepGoalHitDays28: Int,
    val trendStepsWeekOverWeek: Double,
)
