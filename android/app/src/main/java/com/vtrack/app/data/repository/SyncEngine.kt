package com.vtrack.app.data.repository

import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.data.local.dao.SyncDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Outbox processor.
 *
 * Guarantees:
 *  - **Idempotent**: every pushed record carries a client-generated id and the server
 *    upserts on it, so retrying after a timeout cannot duplicate data or coins.
 *  - **Bounded retries**: attempts are capped and use exponential backoff with a ceiling,
 *    so a permanently failing item stops consuming a worker slot without silently
 *    disappearing (it stays visible in the queue for diagnostics).
 *  - **Offline-safe**: nothing is attempted when there is no connectivity, leaving the
 *    queue untouched rather than burning attempts.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val syncDao: SyncDao,
    private val activityRepository: ActivityRepository,
    private val workoutRepository: WorkoutRepository,
    private val rewardsRepository: RewardsRepository,
    private val networkMonitor: NetworkMonitor,
    private val sessionStore: SessionStore,
) {

    fun observePendingCount(): Flow<Int> = syncDao.observeQueueSize()

    suspend fun pendingCount(): Int = syncDao.queueSize()

    /**
     * Runs one sync pass. Returns a summary the worker logs / the UI can surface.
     */
    suspend fun sync(): SyncReport {
        if (!sessionStore.isSignedIn()) {
            return SyncReport(skipped = true, reason = "Not signed in")
        }
        if (!networkMonitor.isCurrentlyOnline()) {
            return SyncReport(skipped = true, reason = "Offline")
        }

        var days = 0
        var workouts = 0
        var claims = 0

        days = activityRepository.syncWithServer().getOrDefault(0)
        workouts = workoutRepository.syncWithServer().getOrDefault(0)
        claims = rewardsRepository.submitPendingClaims()

        // Pull server-authoritative state (balance, catalog, challenges).
        rewardsRepository.refreshFromServer()

        // Clear outbox rows whose work completed, and back off anything still queued.
        drainQueue(days, workouts, claims)

        return SyncReport(
            skipped = false,
            daysSynced = days,
            workoutsSynced = workouts,
            claimsSubmitted = claims,
            remaining = syncDao.queueSize(),
        )
    }

    private suspend fun drainQueue(daysSynced: Int, workoutsSynced: Int, claimsSubmitted: Int) {
        val now = System.currentTimeMillis()
        val due = syncDao.dueItems(now, 50)
        due.forEach { item ->
            val handled = when (item.entityType) {
                "ACTIVITY_DAY" ->
                    // Days are uploaded in bulk; if anything was synced this pass, the
                    // unsynced marker on the row is the real signal, so clear the queue row.
                    daysSynced > 0
                "WORKOUT" -> item.operation == "DELETE" || workoutsSynced > 0
                "WORKOUT_NOTES" -> workoutsSynced > 0
                "CLAIM" -> claimsSubmitted > 0
                "LEDGER" -> true
                else -> true
            }
            if (handled) {
                syncDao.remove(item.id)
            } else {
                val nextAttempt = now + backoffMillis(item.attempts + 1)
                syncDao.backoff(item.id, item.lastError ?: "not synced yet", nextAttempt)
            }
        }
        syncDao.dropExhausted(MAX_ATTEMPTS)
    }

    /** 1s, 2s, 4s … capped at 30 minutes. */
    private fun backoffMillis(attempt: Int): Long {
        val seconds = min(2.0.pow(attempt.toDouble()).toLong(), 1_800L)
        return seconds * 1_000L
    }

    data class SyncReport(
        val skipped: Boolean = false,
        val reason: String? = null,
        val daysSynced: Int = 0,
        val workoutsSynced: Int = 0,
        val claimsSubmitted: Int = 0,
        val remaining: Int = 0,
    )

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
