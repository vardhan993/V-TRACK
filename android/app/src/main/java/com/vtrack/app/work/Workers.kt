package com.vtrack.app.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.WorkoutDao
import com.vtrack.app.data.local.entity.ActivityDayEntity
import com.vtrack.app.data.repository.ActivityRepository
import com.vtrack.app.data.repository.CoachRepository
import com.vtrack.app.data.repository.ExerciseRepository
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.data.repository.SyncEngine
import com.vtrack.app.tracking.Notifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Pushes queued activity and workouts, submits pending claims, and pulls the
 * server-authoritative balance and catalog.
 *
 * Returns success even when offline (the outbox keeps the work), so the scheduler never
 * treats an unreachable network as a permanent failure.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val sessionStore: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!sessionStore.isSignedIn()) return Result.success()
        return try {
            val report = syncEngine.sync()
            if (!report.skipped) {
                Log.i(
                    TAG,
                    "sync: days=${report.daysSynced} workouts=${report.workoutsSynced} " +
                        "claims=${report.claimsSubmitted} remaining=${report.remaining}",
                )
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "sync failed", t)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}

/**
 * Periodic housekeeping:
 *  - rebuilds today's aggregate from the stored buckets (guards against a bucket written
 *    while the process was being killed),
 *  - evaluates local achievements and the weekly coaching plan,
 *  - re-seeds the exercise library if the app was upgraded with new entries.
 */
@HiltWorker
class DailyRollupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val activityRepository: ActivityRepository,
    private val activityDao: ActivityDao,
    private val workoutDao: WorkoutDao,
    private val rewardsRepository: RewardsRepository,
    private val exerciseRepository: ExerciseRepository,
    private val coachRepository: CoachRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        activityRepository.rebuildDay(TimeUtils.todayKey())

        val days = activityDao.recentDays(400)
        val totalWorkouts = workoutDao.observeCompletedWorkoutCount().first()
        val longestDistance = workoutDao.getSessionsBetween(0L, Long.MAX_VALUE)
            .maxOfOrNull { it.distanceMeters } ?: 0.0

        rewardsRepository.seedAchievementCatalog()
        rewardsRepository.evaluateLocalAchievements(
            totalSteps = days.sumOf { it.steps },
            totalWorkouts = totalWorkouts,
            streakDays = currentStreak(days),
            bestStreak = longestStreak(days),
            longestDistanceMeters = longestDistance,
            totalSets = workoutDao.totalSets(),
            totalActiveMinutes = days.sumOf { it.activeMinutes },
        )
        exerciseRepository.ensureSeeded()
        coachRepository.plan()
        Result.success()
    } catch (t: Throwable) {
        Log.w(TAG, "rollup failed", t)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    /** An in-progress day must not break a streak, so today is only skipped when empty. */
    private fun currentStreak(days: List<ActivityDayEntity>): Int {
        val qualifying = days.filter { qualifies(it) }.map { it.dayKey }.toHashSet()
        var streak = 0
        var cursor = LocalDate.now()
        if (!qualifying.contains(cursor.toString())) cursor = cursor.minusDays(1)
        while (qualifying.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun longestStreak(days: List<ActivityDayEntity>): Int {
        val sorted = days.filter { qualifies(it) }.map { it.dayKey }.sorted()
        if (sorted.isEmpty()) return 0
        var best = 1
        var run = 1
        var previous = TimeUtils.parseDayKey(sorted.first())
        for (i in 1 until sorted.size) {
            val current = TimeUtils.parseDayKey(sorted[i])
            run = if (current == previous.plusDays(1)) run + 1 else 1
            if (run > best) best = run
            previous = current
        }
        return best
    }

    private fun qualifies(day: ActivityDayEntity): Boolean = day.goalMet || day.activeMinutes >= 20

    private companion object {
        const val TAG = "DailyRollupWorker"
    }
}

/**
 * Evening reminder. Deliberately quiet: it only fires when the day is largely over, the
 * step goal is unmet, something has actually been recorded, and no workout was completed.
 */
@HiltWorker
class StepGoalReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val activityDao: ActivityDao,
    private val profileDao: ProfileDao,
    private val workoutDao: WorkoutDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Notifications.ensureChannels(applicationContext)
            val profile = profileDao.getProfile() ?: return Result.success()
            val day = activityDao.getDay(TimeUtils.todayKey())
            val steps = day?.steps ?: 0
            val goal = profile.stepGoal
            val workoutsToday = workoutDao.completedSince(TimeUtils.startOfDay(System.currentTimeMillis()))

            if (steps in 1 until goal && workoutsToday == 0) {
                Notifications.notify(
                    applicationContext,
                    Notifications.ID_REMINDER,
                    Notifications.goalReminder(applicationContext, steps, goal),
                )
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w("StepReminder", "reminder failed", t)
            Result.success()
        }
    }
}
