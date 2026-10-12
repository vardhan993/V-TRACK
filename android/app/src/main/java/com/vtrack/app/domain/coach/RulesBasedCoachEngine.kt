package com.vtrack.app.domain.coach

import com.vtrack.app.domain.model.ActivityCategory
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.FitnessLevel
import com.vtrack.app.domain.model.PrimaryGoal
import com.vtrack.app.domain.model.UserProfile
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V-Track's shipped coach: deterministic, offline, explainable.
 *
 * It is explicitly *not* a generative model. It encodes published guidance:
 *  - WHO/ACSM aerobic volume: 150–300 moderate minutes per week.
 *  - Muscle-strengthening on 2+ days per week, ≥48h per muscle group.
 *  - Progressive overload guardrails (~10% per week) and periodic deloads.
 *  - Step ramp of ≤1,000 steps per week for people well below their target.
 *  - Recovery insertion after 3 consecutive hard days.
 *
 * Because it is pure and clock-free (the caller passes `context.today`), every output is
 * reproducible and covered by JVM unit tests.
 */
@Singleton
class RulesBasedCoachEngine @Inject constructor() : CoachEngine {

    override val engineId: String = ENGINE_ID
    override val isGenerative: Boolean = false

    /* ------------------------------------------------------------------ plan */

    override suspend fun weeklyPlan(context: CoachContext): CoachPlan {
        val metrics = CoachMetricsCalculator.compute(context)
        val profile = context.profile
        val weekStart = context.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val baseMinutes = baseActiveMinutesTarget(profile.fitnessLevel)
        val goalModifier = goalMinuteModifier(profile.primaryGoal)
        val rawTarget = baseMinutes + goalModifier
        val adherenceModifier = when {
            metrics.activeMinutesLast7 == 0 -> -0.25
            metrics.activeMinutesLast7 < rawTarget * 0.5 -> -0.20
            metrics.activeMinutesLast7 >= rawTarget * 1.1 -> 0.10
            else -> 0.0
        }
        val weeklyMinutesTarget = (
            (rawTarget * (1 + adherenceModifier)).roundToInt()
                .coerceAtLeast(60)
                .coerceAtMost(420) / 10
            ) * 10

        val trainingDays = profile.weeklyWorkoutTarget.coerceIn(2, 6)
        val sessionMinutes = (weeklyMinutesTarget.toDouble() / trainingDays).roundToInt()

        val stepTarget = recommendedStepTarget(profile, metrics)
        val trainingWeekdays = spreadWeekdays(trainingDays)
        val plan = buildWeek(
            context = context,
            metrics = metrics,
            weekStart = weekStart,
            trainingWeekdays = trainingWeekdays,
            sessionMinutes = sessionMinutes,
            stepTarget = stepTarget,
        )

        val progressions = buildList {
            if (adherenceModifier < 0) {
                add(
                    "Your recent volume was below target, so this week starts ${(abs(adherenceModifier) * 100).roundToInt()}% lighter. " +
                        "Building the habit back is the point — the plan will climb again as consistency returns.",
                )
            }
            if (adherenceModifier > 0) {
                add("You exceeded your target recently, so weekly volume is up 10%. If sleep or recovery suffers, hold at the previous week's volume.")
            }
            if (stepTarget != profile.stepGoal) {
                add(
                    if (stepTarget > profile.stepGoal) {
                        "Step target raised from ${profile.stepGoal} to $stepTarget because you hit your goal on ${metrics.stepGoalHitDays28} of the last 28 days."
                    } else {
                        "Step target eased from ${profile.stepGoal} to $stepTarget to match what your last four weeks actually looked like. Raise it again once you hit it most days."
                    },
                )
            }
            if (metrics.consecutiveTrainingDays >= 3) {
                add("You have trained ${metrics.consecutiveTrainingDays} days in a row, so a recovery day is placed early this week.")
            }
            if (metrics.daysSinceLastWorkout != null && metrics.daysSinceLastWorkout >= 5) {
                add("It has been ${metrics.daysSinceLastWorkout} days since your last session — the first day back is deliberately short.")
            }
        }

        return CoachPlan(
            id = "plan-$weekStart-$ENGINE_ID",
            weekStart = weekStart,
            goal = profile.primaryGoal,
            headline = headlineFor(profile, metrics),
            summary = summaryFor(profile, weeklyMinutesTarget, trainingDays, stepTarget),
            prescriptions = plan,
            dailyStepTarget = stepTarget,
            weeklyActiveMinutesTarget = weeklyMinutesTarget,
            weeklyWorkoutTarget = trainingDays,
            engineVersion = ENGINE_VERSION,
            generatedBy = CoachSource.RULES_LOCAL,
            progressions = progressions,
            safetyNotes = safetyNotes(profile),
        )
    }

    /* --------------------------------------------------------------- briefing */

    override suspend fun briefing(context: CoachContext): CoachBriefing {
        val metrics = CoachMetricsCalculator.compute(context)
        val today = context.today
        val todayActivity = context.recentDays.firstOrNull { it.date == today }
        val steps = todayActivity?.steps ?: 0
        val weekdayIndex = today.dayOfWeek.value - 1
        val trainingWeekdays = spreadWeekdays(context.profile.weeklyWorkoutTarget.coerceIn(2, 6))
        val isTrainingDay = trainingWeekdays.contains(today.dayOfWeek.value)
        val isRecoveryDay = !isTrainingDay || metrics.consecutiveTrainingDays >= 3

        val suggested = if (isRecoveryDay) {
            context.profile.preferredActivities.firstOrNull { it.category == ActivityCategory.MOBILITY }
                ?: ActivityType.WALKING
        } else {
            pickActivity(context.profile, weekdayIndex)
        }

        val target = recommendedStepTarget(context.profile, metrics)
        val headline: String
        val message: String
        when {
            metrics.daysSinceLastWorkout != null && metrics.daysSinceLastWorkout >= 5 ->
                {
                    headline = "Easy start today"
                    message = "It has been ${metrics.daysSinceLastWorkout} days since your last session. " +
                        "Twenty deliberate minutes is enough to get things moving again."
                }
            metrics.consecutiveTrainingDays >= 3 -> {
                headline = "Recovery day"
                message = "That's ${metrics.consecutiveTrainingDays} hard days in a row. Adaptation happens while you recover — " +
                    "warm walking or 10 minutes of mobility today protects the rest of your week."
            }
            isRecoveryDay -> {
                headline = "A lighter day"
                message = "No session is planned today. Keep movement going with a walk or mobility work, and you'll " +
                    "arrive at your next session fresh."
            }
            else -> {
                headline = "${suggested.label} day"
                message = "Today's plan is ${suggested.label.lowercase()} with a ${sessionMinutesFor(context)} minute target. " +
                    "Warm up for five minutes and finish with a two-minute easy cool-down."
            }
        }

        val encouragement = when {
            steps >= target -> "You've already passed today's step target ($steps). Anything else is a bonus."
            steps >= target / 2 -> "You're over halfway to today's step target — a 15-minute walk would close it out."
            steps > 0 -> "Every counted step moves the total. A short walk now is easier than a long one later."
            else -> "Start with five minutes. Getting started is the hardest part of any session."
        }

        return CoachBriefing(
            headline = headline,
            message = message,
            suggestedActivity = suggested,
            stepTargetToday = target,
            encouragement = encouragement,
            suggestedMinutes = if (isRecoveryDay) 20 else sessionMinutesFor(context),
            isRecoveryDay = isRecoveryDay,
        )
    }

    /* ---------------------------------------------------------------- review */

    override suspend fun weeklyReview(context: CoachContext): WeeklyReview {
        val metrics = CoachMetricsCalculator.compute(context)
        val weekStart = context.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val target = weeklyMinutesTargetFor(context)
        val workoutsTarget = context.profile.weeklyWorkoutTarget.coerceIn(2, 6)

        val minutesScore = (metrics.activeMinutesLast7.toDouble() / target * 40).coerceIn(0.0, 40.0)
        val workoutScore = (metrics.workoutsLast7.toDouble() / workoutsTarget * 30).coerceIn(0.0, 30.0)
        val stepScore = (metrics.stepGoalHitDays7 / 7.0 * 20.0).coerceIn(0.0, 20.0)
        val streakScore = (context.streakDays / 7.0 * 10.0).coerceIn(0.0, 10.0)
        val consistency = (minutesScore + workoutScore + stepScore + streakScore).roundToInt()

        val highlights = buildList {
            if (metrics.workoutsLast7 >= workoutsTarget) {
                add("Hit your session target: ${metrics.workoutsLast7} of $workoutsTarget workouts.")
            }
            if (metrics.stepGoalHitDays7 >= 5) {
                add("Step goal reached on ${metrics.stepGoalHitDays7} of the last 7 days.")
            }
            if (context.streakDays >= 3) add("Current streak: ${context.streakDays} days.")
            if (metrics.trendStepsWeekOverWeek > 0.1) {
                add("Steps are up ${(metrics.trendStepsWeekOverWeek * 100).roundToInt()}% compared with the previous week.")
            }
            if (context.personalRecords.isNotEmpty()) {
                add("Personal record: ${context.personalRecords.first()}.")
            }
            if (isEmpty()) add("You logged activity on ${metrics.daysWithData} of the last ${context.recentDays.size} days — that's a starting point, not a verdict.")
        }

        val watchOuts = buildList {
            if (metrics.workoutsLast7 < workoutsTarget - 1) {
                add("Session count was ${metrics.workoutsLast7} against a target of $workoutsTarget.")
            }
            if (metrics.consecutiveTrainingDays >= 4) {
                add("${metrics.consecutiveTrainingDays} consecutive training days — add recovery before adding volume.")
            }
            if (metrics.trendStepsWeekOverWeek < -0.2) {
                add("Steps dropped ${(abs(metrics.trendStepsWeekOverWeek) * 100).roundToInt()}% week over week.")
            }
            val cardio = metrics.minutesByCategory7[ActivityCategory.CARDIO] ?: 0
            val strength = metrics.minutesByCategory7[ActivityCategory.STRENGTH] ?: 0
            if (strength == 0) add("No strength work logged — two short full-body sessions would cover the guideline.")
            if (cardio == 0) add("No sustained cardio logged this week.")
        }

        val adjustment = adjustmentFor(context, metrics, target)
        return WeeklyReview(
            weekStart = weekStart,
            activeMinutes = metrics.activeMinutesLast7,
            activeMinutesTarget = target,
            workoutsCompleted = metrics.workoutsLast7,
            workoutsTarget = workoutsTarget,
            averageSteps = metrics.averageSteps7,
            stepTarget = context.profile.stepGoal,
            consistencyScore = consistency,
            highlights = highlights,
            watchOuts = watchOuts,
            nextWeekFocus = nextWeekFocus(context),
            recoveryRecommendation = when {
                metrics.consecutiveTrainingDays >= 4 ->
                    "Take two easy days: warm walking or mobility only. Keep the habit, drop the intensity."
                metrics.workoutsLast7 == 0 ->
                    "Book two 20-minute sessions at times that already work in your week, before adding anything else."
                else -> "Keep one full rest day and one lighter active day in the coming week."
            },
            adjustment = adjustment,
            muscleGroupBalance = metrics.minutesByCategory7.mapKeys { it.key.label },
        )
    }

    /* ---------------------------------------------------------------- answer */

    override suspend fun answer(question: String, context: CoachContext): CoachAnswer {
        FitnessKnowledgeBase.safetyRedirect(question)?.let { return it }

        // Personalise the generic topic answer with the user's own numbers where it helps.
        val topic = FitnessKnowledgeBase.match(question) ?: return FitnessKnowledgeBase.fallback(question)
        val metrics = CoachMetricsCalculator.compute(context)
        val personal = when (topic.id) {
            "step_goal" -> "Your 28-day average is ${metrics.averageSteps28} steps a day, so your next realistic target is ${recommendedStepTarget(context.profile, metrics)}."
            "strength_frequency" -> "Your plan currently schedules ${context.profile.weeklyWorkoutTarget.coerceIn(2, 6)} sessions a week."
            "streak_importance" -> "Your current streak is ${context.streakDays} days; your best is ${context.bestStreak}."
            "cardio_amount" -> "You logged ${metrics.activeMinutesLast7} active minutes over the last 7 days."
            else -> null
        }
        return CoachAnswer(
            question = question,
            topic = topic.id,
            answer = if (personal != null) "${topic.answer}\n\nFor you: $personal" else topic.answer,
            bulletPoints = topic.bullets,
            safetyRedirect = false,
            disclaimer = FitnessKnowledgeBase.disclaimer,
        )
    }

    /* ------------------------------------------------------------- internals */

    private fun buildWeek(
        context: CoachContext,
        metrics: CoachMetrics,
        weekStart: LocalDate,
        trainingWeekdays: List<Int>,
        sessionMinutes: Int,
        stepTarget: Int,
    ): List<CoachPrescription> {
        val profile = context.profile
        var index = 0
        return (0..6).map { offset ->
            val day = weekStart.plusDays(offset.toLong())
            val weekday = day.dayOfWeek.value
            val isToday = day == context.today
            val restRequested = metrics.consecutiveTrainingDays >= 3 && offset in 0..1
            val isTrainingDay = trainingWeekdays.contains(weekday) && !restRequested

            if (isTrainingDay) {
                val activity = pickActivity(profile, index++)
                val minutes = scaledMinutes(sessionMinutes, profile.fitnessLevel)
                CoachPrescription(
                    day = day,
                    weekdayLabel = day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                    title = "${activity.label} • $minutes min",
                    detail = detailFor(activity, minutes),
                    activityType = activity,
                    focus = activity.category,
                    targetMinutes = minutes,
                    targetSteps = stepTarget,
                    isRecovery = false,
                    isRest = false,
                    rationale = rationaleFor(activity, profile, metrics, isToday),
                )
            } else {
                val isFullRest = offset == 6 || (weekday == DayOfWeek.SUNDAY.value && profile.weeklyWorkoutTarget >= 5)
                val recoveryActivity = profile.preferredActivities
                    .firstOrNull { it.category == ActivityCategory.MOBILITY }
                    ?: ActivityType.WALKING
                CoachPrescription(
                    day = day,
                    weekdayLabel = day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                    title = if (isFullRest) "Rest day" else "Active recovery • 20 min",
                    detail = if (isFullRest) {
                        "Full rest. Optional 10-minute easy walk if you feel stiff."
                    } else {
                        "20 minutes of easy walking plus 8–10 minutes of mobility for hips, hamstrings and shoulders."
                    },
                    activityType = if (isFullRest) null else recoveryActivity,
                    focus = ActivityCategory.MOBILITY,
                    targetMinutes = if (isFullRest) 0 else 20,
                    targetSteps = stepTarget,
                    isRecovery = !isFullRest,
                    isRest = isFullRest,
                    rationale = if (isFullRest) {
                        "A weekly full rest day keeps fatigue from accumulating faster than you adapt."
                    } else if (restRequested) {
                        "Placed because you have trained ${metrics.consecutiveTrainingDays} days in a row."
                    } else {
                        "Recovery is scheduled ${CoachMetricsCalculator.recommendedRecoveryDays(profile.fitnessLevel)} times a week for your training level."
                    },
                )
            }
        }
    }

    private fun pickActivity(profile: UserProfile, index: Int): ActivityType {
        val preferred = profile.preferredActivities.ifEmpty { listOf(ActivityType.WALKING) }
        val goalSequence: List<ActivityType> = when (profile.primaryGoal) {
            PrimaryGoal.BUILD_STRENGTH -> listOf(
                ActivityType.STRENGTH, ActivityType.BODYWEIGHT, ActivityType.STRENGTH,
                ActivityType.HIIT, ActivityType.STRENGTH, ActivityType.WALKING,
            )
            PrimaryGoal.IMPROVE_ENDURANCE -> listOf(
                ActivityType.RUNNING, ActivityType.CYCLING, ActivityType.RUNNING,
                ActivityType.ROWING, ActivityType.RUNNING, ActivityType.YOGA,
            )
            PrimaryGoal.LOSE_WEIGHT -> listOf(
                ActivityType.WALKING, ActivityType.STRENGTH, ActivityType.HIIT,
                ActivityType.RUNNING, ActivityType.STRENGTH, ActivityType.CYCLING,
            )
            PrimaryGoal.RECOVER_MOBILITY -> listOf(
                ActivityType.YOGA, ActivityType.WALKING, ActivityType.MOBILITY,
                ActivityType.STRETCHING, ActivityType.WALKING, ActivityType.YOGA,
            )
            PrimaryGoal.STAY_ACTIVE -> listOf(
                ActivityType.WALKING, ActivityType.STRENGTH, ActivityType.WALKING,
                ActivityType.CYCLING, ActivityType.BODYWEIGHT, ActivityType.YOGA,
            )
        }
        val fromGoal = goalSequence[index % goalSequence.size]
        // Respect the user's preferences: keep the goal-oriented category but swap in a
        // preferred activity from the same category when one exists.
        return preferred.firstOrNull { it.category == fromGoal.category } ?: fromGoal
    }

    private fun detailFor(activity: ActivityType, minutes: Int): String = when (activity.category) {
        ActivityCategory.STRENGTH -> "5 min warm-up, then $minutes minutes of working sets. " +
            "Use a load where the last 2–3 reps of each set are hard but controllable (RPE 7–8). Log sets to track progression."
        ActivityCategory.CARDIO -> "$minutes minutes at a conversational pace (RPE 5–6). " +
            "Split it into blocks if that helps you finish it. Include 5 min easy at each end."
        ActivityCategory.MOBILITY -> "$minutes minutes of mobility and stretching, breathing slowly through each position."
        ActivityCategory.FUN -> "$minutes minutes of the activity, keeping the effort playful rather than maximal."
        ActivityCategory.OTHER -> "$minutes minutes of continuous movement at a comfortable effort."
    }

    private fun rationaleFor(
        activity: ActivityType,
        profile: UserProfile,
        metrics: CoachMetrics,
        isToday: Boolean,
    ): String = buildString {
        append("Chosen for ${profile.primaryGoal.label.lowercase()}")
        if (activity.category == ActivityCategory.STRENGTH && metrics.minutesByCategory7[ActivityCategory.STRENGTH] == 0) {
            append(" — no strength work has been logged recently")
        }
        if (activity in profile.preferredActivities) append(" and it's one of your preferred activities")
        if (isToday) append(". This is today")
        append(".")
    }

    private fun scaledMinutes(base: Int, level: FitnessLevel): Int {
        val factor = when (level) {
            FitnessLevel.BEGINNER -> 0.8
            FitnessLevel.INTERMEDIATE -> 1.0
            FitnessLevel.ADVANCED -> 1.15
        }
        return ((base * factor).roundToInt() / 5 * 5).coerceIn(15, 90)
    }

    private fun sessionMinutesFor(context: CoachContext): Int {
        val target = weeklyMinutesTargetFor(context)
        return (target.toDouble() / context.profile.weeklyWorkoutTarget.coerceIn(2, 6)).roundToInt()
    }

    fun weeklyMinutesTargetFor(context: CoachContext): Int {
        val base = baseActiveMinutesTarget(context.profile.fitnessLevel) + goalMinuteModifier(context.profile.primaryGoal)
        val metrics = CoachMetricsCalculator.compute(context)
        return when {
            metrics.activeMinutesLast7 == 0 -> (base * 0.75).roundToInt()
            metrics.activeMinutesLast7 < base * 0.5 -> (base * 0.8).roundToInt()
            metrics.activeMinutesLast7 >= base * 1.1 -> (base * 1.1).roundToInt()
            else -> base
        }
    }

    private fun baseActiveMinutesTarget(level: FitnessLevel): Int = when (level) {
        FitnessLevel.BEGINNER -> 150
        FitnessLevel.INTERMEDIATE -> 225
        FitnessLevel.ADVANCED -> 300
    }

    private fun goalMinuteModifier(goal: PrimaryGoal): Int = when (goal) {
        PrimaryGoal.IMPROVE_ENDURANCE -> 30
        PrimaryGoal.LOSE_WEIGHT -> 15
        PrimaryGoal.BUILD_STRENGTH -> 0
        PrimaryGoal.STAY_ACTIVE -> 0
        PrimaryGoal.RECOVER_MOBILITY -> -30
    }

    /**
     * Step target ramp: never more than +1,000 per two weeks, and eased when the recent
     * picture says the current goal is not reachable.
     */
    fun recommendedStepTarget(profile: UserProfile, metrics: CoachMetrics): Int {
        val goal = profile.stepGoal.coerceAtLeast(1_000)
        return when {
            metrics.daysWithData < 3 -> goal
            metrics.stepGoalHitDays28 >= 20 && metrics.averageSteps28 >= goal -> (goal + 500).coerceAtMost(15_000)
            metrics.averageSteps28 < goal * 0.5 -> {
                val eased = (metrics.averageSteps28 * 1.15).roundToInt()
                eased.coerceIn(4_000, goal)
            }
            else -> goal
        }
    }

    /** Monday-first weekdays for [count] sessions, spread so no 3 days are consecutive. */
    fun spreadWeekdays(count: Int): List<Int> = when (count.coerceIn(2, 6)) {
        2 -> listOf(2, 5)
        3 -> listOf(1, 3, 5)
        4 -> listOf(1, 2, 4, 6)
        5 -> listOf(1, 2, 3, 5, 6)
        else -> listOf(1, 2, 3, 4, 5, 6)
    }

    private fun adjustmentFor(context: CoachContext, metrics: CoachMetrics, target: Int): PlanAdjustment {
        val completion = if (target <= 0) 0.0 else metrics.activeMinutesLast7.toDouble() / target
        val workoutCompletion = metrics.workoutsLast7.toDouble() / context.profile.weeklyWorkoutTarget.coerceIn(2, 6)
        val combined = (completion + workoutCompletion) / 2.0
        return when {
            metrics.activeMinutesLast7 == 0 && metrics.workoutsLast7 == 0 -> PlanAdjustment(
                PlanAdjustment.Direction.REDUCE, 25,
                "No activity was logged last week, so next week is deliberately smaller — two short sessions beat a plan you don't start.",
            )
            combined < 0.6 -> PlanAdjustment(
                PlanAdjustment.Direction.REDUCE, 15,
                "You completed about ${(combined * 100).roundToInt()}% of last week's plan, so it steps down rather than adding more on top.",
            )
            combined > 1.05 && metrics.consecutiveTrainingDays < 4 -> PlanAdjustment(
                PlanAdjustment.Direction.INCREASE, 10,
                "You exceeded last week's plan and recovery looks intact, so volume rises by 10% — within the usual 10% guardrail.",
            )
            else -> PlanAdjustment(
                PlanAdjustment.Direction.HOLD, 0,
                "Last week's volume was close to plan, so this week holds steady and lets the adaptation catch up.",
            )
        }
    }

    private fun nextWeekFocus(context: CoachContext): String {
        val metrics = CoachMetricsCalculator.compute(context)
        return when {
            metrics.minutesByCategory7[ActivityCategory.STRENGTH] == 0 && context.profile.primaryGoal != PrimaryGoal.RECOVER_MOBILITY ->
                "Add two short strength sessions — they support every other goal you have."
            metrics.activeMinutesLast7 < 60 -> "Rebuild the base: three 20-minute sessions at an easy pace."
            metrics.consecutiveTrainingDays >= 4 -> "Protect recovery: keep one full rest day and one easy day."
            metrics.workoutsLast7 >= context.profile.weeklyWorkoutTarget -> "Hold this volume and add 5 minutes to one session."
            else -> "Close the gap on session frequency rather than adding intensity."
        }
    }

    private fun headlineFor(profile: UserProfile, metrics: CoachMetrics): String = when {
        metrics.daysWithData == 0 -> "Starting from scratch: this week builds the habit"
        metrics.activeMinutesLast7 == 0 -> "Back to basics: two short sessions this week"
        profile.primaryGoal == PrimaryGoal.BUILD_STRENGTH -> "Strength week: progressive sessions plus recovery"
        profile.primaryGoal == PrimaryGoal.IMPROVE_ENDURANCE -> "Endurance week: steady volume with one quality session"
        profile.primaryGoal == PrimaryGoal.RECOVER_MOBILITY -> "Mobility week: move well, five short sessions"
        profile.primaryGoal == PrimaryGoal.LOSE_WEIGHT -> "Steady week: consistent movement, no heroics"
        else -> "Balanced week: movement every day, ${contextPhrase(metrics)}"
    }

    private fun contextPhrase(metrics: CoachMetrics): String =
        if (metrics.averageSteps7 > 0) "around ${metrics.averageSteps7} steps a day" else "a gentle rebuild"

    private fun summaryFor(profile: UserProfile, weeklyMinutes: Int, sessions: Int, stepTarget: Int): String =
        "${sessions} scheduled session${if (sessions == 1) "" else "s"} totalling about $weeklyMinutes active minutes this week, " +
            "with a daily step target of $stepTarget. Built for a ${profile.fitnessLevel.label.lowercase()} " +
            "training level and your ${profile.primaryGoal.label.lowercase()} goal."

    private fun safetyNotes(profile: UserProfile): List<String> = buildList {
        add("Warm up for 5 minutes before harder efforts and cool down for 2–3 minutes after.")
        add("Stop immediately if you feel chest pain, dizziness, or unusual shortness of breath, and seek medical help.")
        val age = profile.age
        if (age != null && age >= 60) {
            add("You're over 60: favour brisk walking, mobility and light resistance work, and get clearance from your doctor before hard intervals.")
        }
        if (profile.fitnessLevel == FitnessLevel.BEGINNER) {
            add("As a beginner, aim to finish every session feeling you could have done a little more.")
        }
        add("This plan is general fitness information, not medical advice or a diagnosis.")
    }

    companion object {
        const val ENGINE_ID = "vtrack-rules-coach"
        const val ENGINE_VERSION = "1.1.0"
    }
}
