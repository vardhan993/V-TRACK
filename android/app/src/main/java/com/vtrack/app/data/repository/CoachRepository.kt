package com.vtrack.app.data.repository

import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.local.Mappers.toDomain
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.RewardsDao
import com.vtrack.app.data.local.dao.WorkoutDao
import com.vtrack.app.data.local.entity.CoachPlanEntity
import com.vtrack.app.data.remote.VTrackApi
import com.vtrack.app.data.remote.dto.CoachAskRequest
import com.vtrack.app.domain.coach.CoachAnswer
import com.vtrack.app.domain.coach.CoachBriefing
import com.vtrack.app.domain.coach.CoachContext
import com.vtrack.app.domain.coach.CoachPlan
import com.vtrack.app.domain.coach.CoachPrescription
import com.vtrack.app.domain.coach.CoachSource
import com.vtrack.app.domain.coach.RulesBasedCoachEngine
import com.vtrack.app.domain.coach.WeeklyReview
import com.vtrack.app.domain.model.ActivityCategory
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.UserProfile
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the coaching context from local data and runs the chosen [CoachEngine].
 *
 * Order of preference:
 *  1. The local rules engine — always available, fully offline, deterministic.
 *  2. The server rules engine (same rules, evaluated server-side) when the user allows
 *     activity sharing **and** the deployment has enabled the optional generative flag.
 *     That generative path is opt-in, never required, and falls back to local rules on
 *     any failure, so the coach never becomes unavailable because of a network problem.
 */
@Singleton
class CoachRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val activityDao: ActivityDao,
    private val workoutDao: WorkoutDao,
    private val rewardsDao: RewardsDao,
    private val engine: RulesBasedCoachEngine,
    private val api: VTrackApi,
    private val networkMonitor: NetworkMonitor,
    private val sessionStore: SessionStore,
    private val json: Json,
) {

    /** Builds the context the engines reason over (last 28 days, last 30 sessions). */
    suspend fun buildContext(): CoachContext {
        val profile = profileDao.getProfile()?.toDomain() ?: UserProfile.empty()
        val days = activityDao.recentDays(28).map { it.toDomain() }.reversed()
        val workouts = workoutDao.getSessionsBetween(0L, Long.MAX_VALUE)
            .take(30)
            .map { it.toDomain() }
        val streakDays = computeStreak()
        val bestStreak = bestStreak()
        val records = workoutDao.recentSets(200).groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
            val best = sets.maxByOrNull { it.weightKg ?: 0.0 }?.weightKg ?: return@mapNotNull null
            val name = workoutDao.getExercise(id)?.name ?: return@mapNotNull null
            "$name ${"%.1f".format(best)} kg"
        }
        return CoachContext(
            profile = profile,
            today = LocalDate.now(),
            recentDays = days,
            recentWorkouts = workouts,
            streakDays = streakDays,
            bestStreak = bestStreak,
            coinsEarnedLast7Days = rewardsDao.earnedFromActivitySince(
                System.currentTimeMillis() - 7L * 24 * 3600 * 1000,
            ),
            personalRecords = records.take(3),
        )
    }

    /** Cached plan for this week, regenerated when the week changes or data moves. */
    suspend fun plan(forceRefresh: Boolean = false): CoachPlan {
        val context = buildContext()
        val weekStart = context.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        val cached = rewardsDao.latestPlan()
        if (!forceRefresh && cached != null && cached.weekStartDayKey == weekStart) {
            decodePlan(cached)?.let { return it }
        }
        val plan = tryServerPlan(context) ?: engine.weeklyPlan(context)
        rewardsDao.upsertCoachPlan(encodePlan(plan))
        return plan
    }

    suspend fun briefing(): CoachBriefing = engine.briefing(buildContext())

    suspend fun review(): WeeklyReview = engine.weeklyReview(buildContext())

    suspend fun answer(question: String): CoachAnswer {
        val context = buildContext()
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            return engine.answer("general", context)
        }
        val remote = tryServerAnswer(trimmed)
        return remote ?: engine.answer(trimmed, context)
    }

    fun suggestions(): List<String> = com.vtrack.app.domain.coach.FitnessKnowledgeBase.suggestions()

    /* --------------------------------------------------------------- server */

    private suspend fun tryServerPlan(context: CoachContext): CoachPlan? {
        if (!context.profile.shareActivityWithCoach) return null
        if (!networkMonitor.isCurrentlyOnline() || !sessionStore.isSignedIn()) return null
        val dto = runCatching { api.coachPlan() }.getOrNull() ?: return null
        return CoachPlan(
            id = dto.id,
            weekStart = runCatching { LocalDate.parse(dto.weekStartDayKey) }.getOrDefault(context.today),
            goal = context.profile.primaryGoal,
            headline = dto.headline,
            summary = dto.summary,
            prescriptions = dto.days.map { day ->
                CoachPrescription(
                    day = runCatching { LocalDate.parse(day.dayKey) }.getOrDefault(context.today),
                    weekdayLabel = runCatching {
                        LocalDate.parse(day.dayKey).dayOfWeek.name.take(3)
                            .lowercase().replaceFirstChar { it.uppercase() }
                    }.getOrDefault(""),
                    title = day.title,
                    detail = day.detail,
                    activityType = day.activityType?.let { ActivityType.fromStorage(it) },
                    focus = ActivityCategory.CARDIO,
                    targetMinutes = day.targetMinutes,
                    targetSteps = day.targetSteps,
                    isRecovery = day.isRecovery,
                    isRest = day.isRest,
                    rationale = day.rationale,
                )
            },
            dailyStepTarget = dto.dailyStepTarget,
            weeklyActiveMinutesTarget = dto.weeklyActiveMinutesTarget,
            weeklyWorkoutTarget = dto.weeklyWorkoutTarget,
            engineVersion = dto.engineVersion,
            generatedBy = if (dto.generatedBy.contains("model", true)) {
                CoachSource.GENERATIVE_MODEL
            } else {
                CoachSource.RULES_SERVER
            },
            progressions = dto.progressions,
            safetyNotes = dto.safetyNotes,
        )
    }

    private suspend fun tryServerAnswer(question: String): CoachAnswer? {
        val profile = buildContext().profile
        if (!profile.shareActivityWithCoach) return null
        if (!networkMonitor.isCurrentlyOnline() || !sessionStore.isSignedIn()) return null
        val dto = runCatching { api.coachAsk(CoachAskRequest(question)) }.getOrNull() ?: return null
        return CoachAnswer(
            question = question,
            topic = dto.topic,
            answer = dto.answer,
            bulletPoints = dto.bulletPoints,
            safetyRedirect = dto.safetyRedirect,
            disclaimer = dto.disclaimer,
        )
    }

    /* ------------------------------------------------------------- helpers */

    private suspend fun computeStreak(): Int {
        val days = activityDao.recentDays(90)
        if (days.isEmpty()) return 0
        val qualified = days.filter { it.goalMet || it.activeMinutes >= 20 }.map { it.dayKey }.toHashSet()
        var streak = 0
        var cursor = LocalDate.now()
        // Today in progress must not break the streak.
        if (!qualified.contains(cursor.toString())) cursor = cursor.minusDays(1)
        while (qualified.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private suspend fun bestStreak(): Int {
        val qualified = activityDao.recentDays(365)
            .filter { it.goalMet || it.activeMinutes >= 20 }
            .map { it.dayKey }
            .sorted()
        if (qualified.isEmpty()) return 0
        var best = 1
        var run = 1
        var previous = TimeUtils.parseDayKey(qualified.first())
        for (i in 1 until qualified.size) {
            val current = TimeUtils.parseDayKey(qualified[i])
            run = if (current == previous.plusDays(1)) run + 1 else 1
            if (run > best) best = run
            previous = current
        }
        return best
    }

    /**
     * The plan is cached as its own serializable payload rather than by serialising the
     * domain model, which keeps `LocalDate`/enum handling explicit and lets the payload
     * evolve without breaking older cached rows.
     */
    private fun encodePlan(plan: CoachPlan): CoachPlanEntity = CoachPlanEntity(
        id = plan.id,
        createdAt = System.currentTimeMillis(),
        weekStartDayKey = plan.weekStart.toString(),
        goal = plan.goal.name,
        summary = plan.summary,
        engineVersion = plan.engineVersion,
        generatedBy = plan.generatedBy.name,
        payloadJson = json.encodeToString(
            PlanPayload.serializer(),
            PlanPayload(
                id = plan.id,
                weekStart = plan.weekStart.toString(),
                goal = plan.goal.name,
                headline = plan.headline,
                summary = plan.summary,
                dailyStepTarget = plan.dailyStepTarget,
                weeklyActiveMinutesTarget = plan.weeklyActiveMinutesTarget,
                weeklyWorkoutTarget = plan.weeklyWorkoutTarget,
                engineVersion = plan.engineVersion,
                generatedBy = plan.generatedBy.name,
                progressions = plan.progressions,
                safetyNotes = plan.safetyNotes,
                days = plan.prescriptions.map { p ->
                    DayPayload(
                        day = p.day.toString(),
                        weekdayLabel = p.weekdayLabel,
                        title = p.title,
                        detail = p.detail,
                        activityType = p.activityType?.name,
                        focus = p.focus.name,
                        targetMinutes = p.targetMinutes,
                        targetSteps = p.targetSteps,
                        isRecovery = p.isRecovery,
                        isRest = p.isRest,
                        rationale = p.rationale,
                    )
                },
            ),
        ),
    )

    private fun decodePlan(entity: CoachPlanEntity): CoachPlan? = runCatching {
        val payload = json.decodeFromString(PlanPayload.serializer(), entity.payloadJson)
        CoachPlan(
            id = payload.id,
            weekStart = LocalDate.parse(payload.weekStart),
            goal = com.vtrack.app.domain.model.PrimaryGoal.fromStorage(payload.goal),
            headline = payload.headline,
            summary = payload.summary,
            prescriptions = payload.days.map { d ->
                CoachPrescription(
                    day = LocalDate.parse(d.day),
                    weekdayLabel = d.weekdayLabel,
                    title = d.title,
                    detail = d.detail,
                    activityType = d.activityType?.let { ActivityType.fromStorage(it) },
                    focus = runCatching { ActivityCategory.valueOf(d.focus) }
                        .getOrDefault(ActivityCategory.OTHER),
                    targetMinutes = d.targetMinutes,
                    targetSteps = d.targetSteps,
                    isRecovery = d.isRecovery,
                    isRest = d.isRest,
                    rationale = d.rationale,
                )
            },
            dailyStepTarget = payload.dailyStepTarget,
            weeklyActiveMinutesTarget = payload.weeklyActiveMinutesTarget,
            weeklyWorkoutTarget = payload.weeklyWorkoutTarget,
            engineVersion = payload.engineVersion,
            generatedBy = runCatching { CoachSource.valueOf(payload.generatedBy) }
                .getOrDefault(CoachSource.RULES_LOCAL),
            progressions = payload.progressions,
            safetyNotes = payload.safetyNotes,
        )
    }.getOrNull()
}

@kotlinx.serialization.Serializable
private data class PlanPayload(
    val id: String,
    val weekStart: String,
    val goal: String,
    val headline: String,
    val summary: String,
    val dailyStepTarget: Int,
    val weeklyActiveMinutesTarget: Int,
    val weeklyWorkoutTarget: Int,
    val engineVersion: String,
    val generatedBy: String,
    val progressions: List<String> = emptyList(),
    val safetyNotes: List<String> = emptyList(),
    val days: List<DayPayload> = emptyList(),
)

@kotlinx.serialization.Serializable
private data class DayPayload(
    val day: String,
    val weekdayLabel: String,
    val title: String,
    val detail: String,
    val activityType: String? = null,
    val focus: String = "OTHER",
    val targetMinutes: Int = 0,
    val targetSteps: Int = 0,
    val isRecovery: Boolean = false,
    val isRest: Boolean = false,
    val rationale: String = "",
)
