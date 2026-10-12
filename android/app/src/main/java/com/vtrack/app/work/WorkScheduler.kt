package com.vtrack.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central definition of background work.
 *
 * Every periodic job uses `KEEP` policy so re-scheduling on each app start does not reset
 * the schedule (with `UPDATE`/`REPLACE`, a frequently-opened app would postpone its own
 * sync indefinitely).
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleAll() {
        scheduleSync()
        scheduleRollup()
        scheduleReminder()
    }

    /** Cloud sync: every two hours while online. */
    fun scheduleSync() {
        val work = PeriodicWorkRequestBuilder<SyncWorker>(2, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
    }

    /** Immediate sync attempt (after a workout finishes, at app start, after a claim). */
    fun syncNow() {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    /** Rolls aggregates up, evaluates achievements and prunes old rows. */
    fun scheduleRollup() {
        val work = PeriodicWorkRequestBuilder<DailyRollupWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ROLLUP_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
    }

    /** Evening nudge when the day's step goal is not met. */
    fun scheduleReminder() {
        val target = LocalDateTime.now()
            .with(LocalTime.of(19, 30))
            .let { if (it.isBefore(LocalDateTime.now())) it.plusDays(1) else it }
        val delayMinutes = ChronoUnit.MINUTES.between(LocalDateTime.now(), target).coerceAtLeast(1)
        val work = PeriodicWorkRequestBuilder<StepGoalReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            REMINDER_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
    }

    fun cancelAll() {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(SYNC_PERIODIC)
        manager.cancelUniqueWork(ROLLUP_PERIODIC)
        manager.cancelUniqueWork(REMINDER_PERIODIC)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val SYNC_PERIODIC = "vtrack.sync.periodic"
        const val ROLLUP_PERIODIC = "vtrack.rollup.periodic"
        const val REMINDER_PERIODIC = "vtrack.reminder.periodic"
    }
}
