package com.vtrack.app.data.repository

import com.vtrack.app.core.error.mapToVTrackError
import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.local.Mappers.toDomain
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.SyncDao
import com.vtrack.app.data.local.entity.ActivityDayEntity
import com.vtrack.app.data.local.entity.StepSampleEntity
import com.vtrack.app.data.local.entity.SyncQueueEntity
import com.vtrack.app.data.remote.VTrackApi
import com.vtrack.app.data.remote.dto.ActivityDayUpload
import com.vtrack.app.data.remote.dto.ActivitySyncRequest
import com.vtrack.app.data.remote.dto.StepBucketDto
import com.vtrack.app.domain.engine.CalorieCalculator
import com.vtrack.app.domain.model.DailyActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records device-measured activity and derives the daily aggregate everything else reads.
 *
 * Data-integrity rules that make the "verified vs estimated" story honest:
 *  1. Step samples are stored in hourly buckets per source; nothing is invented.
 *  2. The daily total uses `max(sensor, healthConnect)` rather than a sum, because the
 *     two sources observe the same walking (summing them would inflate the day).
 *  3. Any day whose numbers change is pushed to the outbox; the server independently
 *     re-derives reward eligibility and returns what it accepted as `verifiedSteps`.
 */
@Singleton
class ActivityRepository @Inject constructor(
    private val activityDao: ActivityDao,
    private val profileDao: ProfileDao,
    private val syncDao: SyncDao,
    private val api: VTrackApi,
    private val networkMonitor: NetworkMonitor,
    private val sessionStore: SessionStore,
) {

    companion object {
        const val SOURCE_SENSOR = "SENSOR"
        const val SOURCE_HEALTH_CONNECT = "HEALTH_CONNECT"
        const val SOURCE_MANUAL = "MANUAL"

        // Per-hour cap guards against a corrupt or malicious sensor stream.
        private const val MAX_STEPS_PER_HOUR = 6_000
    }

    /* ------------------------------------------------------------ recording */

    /**
     * Adds a real sensor delta to the current hour bucket and refreshes the day.
     * [stepDelta] must be a non-negative difference between two hardware readings.
     */
    suspend fun recordSteps(
        stepDelta: Int,
        activeSeconds: Int,
        source: String = SOURCE_SENSOR,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = TimeUtils.zone(),
        heightCm: Double? = null,
        weightKg: Double? = null,
    ) {
        if (stepDelta <= 0) return
        val dayKey = TimeUtils.dayKey(now, zone)
        val hour = TimeUtils.hourOfDay(now, zone)

        val existing = activityDao.findSample(dayKey, hour, source)
        val newSteps = (existing?.steps ?: 0) + stepDelta
        val cappedSteps = newSteps.coerceAtMost(MAX_STEPS_PER_HOUR)

        val activity = com.vtrack.app.domain.model.ActivityType.WALKING
        val distance = CalorieCalculator.distanceFromSteps(cappedSteps, heightCm, activity)
        val calories = CalorieCalculator.fromSteps(cappedSteps, weightKg, heightCm, activity)

        activityDao.upsertSample(
            StepSampleEntity(
                dayKey = dayKey,
                hourOfDay = hour,
                source = source,
                steps = cappedSteps,
                distanceMeters = distance,
                calories = calories,
                activeSeconds = (existing?.activeSeconds ?: 0) + activeSeconds.coerceAtLeast(0),
                updatedAt = now,
                syncedAt = null,
            ),
        )

        rebuildDay(dayKey, zone, now)
        enqueueDay(dayKey)
    }

    /**
     * Imports a Health Connect (or other) observation for a past hour. Deduplicated by
     * the caller through `imported_record`, and stored in its own source bucket so the
     * device's own measurement is never overwritten.
     */
    suspend fun importExternalSteps(
        dayKey: String,
        hour: Int,
        steps: Int,
        distanceMeters: Double,
        calories: Double,
        activeSeconds: Int,
        source: String = SOURCE_HEALTH_CONNECT,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = TimeUtils.zone(),
    ) {
        if (steps <= 0) return
        val existing = activityDao.findSample(dayKey, hour, source)
        // Imports carry absolute values for the bucket, so take the largest observation
        // rather than adding, which would double count repeated imports.
        if (existing != null && existing.steps >= steps) return
        activityDao.upsertSample(
            StepSampleEntity(
                dayKey = dayKey,
                hourOfDay = hour,
                source = source,
                steps = steps,
                distanceMeters = distanceMeters,
                calories = calories,
                activeSeconds = activeSeconds,
                updatedAt = now,
                syncedAt = null,
            ),
        )
        rebuildDay(dayKey, zone, now)
        enqueueDay(dayKey)
    }

    /** Recomputes [ActivityDayEntity] from its hourly buckets. */
    suspend fun rebuildDay(
        dayKey: String,
        zone: ZoneId = TimeUtils.zone(),
        now: Long = System.currentTimeMillis(),
    ) {
        val samples = activityDao.getSamples(dayKey)
        val goal = profileDao.getProfile()?.stepGoal ?: 8_000
        val previous = activityDao.getDay(dayKey)

        if (samples.isEmpty()) {
            if (previous != null) {
                activityDao.upsertDay(
                    previous.copy(
                        steps = 0,
                        distanceMeters = 0.0,
                        calories = 0.0,
                        activeMinutes = 0,
                        goalMet = false,
                        updatedAt = now,
                        syncedAt = null,
                    ),
                )
            }
            return
        }

        val device = samples.filter { it.source == SOURCE_SENSOR || it.source == SOURCE_MANUAL }
        val imported = samples.filter { it.source == SOURCE_HEALTH_CONNECT }

        val deviceSteps = device.sumOf { it.steps }
        val importedSteps = imported.sumOf { it.steps }
        val chosenSteps = maxOf(deviceSteps, importedSteps)
        val chosenIsDevice = deviceSteps >= importedSteps
        val chosenDistance = if (chosenIsDevice) {
            device.sumOf { it.distanceMeters }
        } else {
            imported.sumOf { it.distanceMeters }
        }
        val chosenCalories = if (chosenIsDevice) {
            device.sumOf { it.calories }
        } else {
            imported.sumOf { it.calories }
        }
        val activeSeconds = samples.sumOf { it.activeSeconds }

        activityDao.upsertDay(
            ActivityDayEntity(
                dayKey = dayKey,
                steps = chosenSteps,
                verifiedSteps = previous?.verifiedSteps ?: 0,
                distanceMeters = chosenDistance,
                calories = chosenCalories,
                activeMinutes = activeSeconds / 60,
                goalSteps = goal,
                goalMet = chosenSteps >= goal,
                firstSampleAt = previous?.firstSampleAt ?: samples.minOf { it.updatedAt },
                lastSampleAt = samples.maxOf { it.updatedAt },
                updatedAt = now,
                syncedAt = previous?.syncedAt,
            ),
        )
    }

    private suspend fun enqueueDay(dayKey: String) {
        syncDao.enqueue(
            SyncQueueEntity(
                entityType = "ACTIVITY_DAY",
                entityId = dayKey,
                operation = "UPSERT",
                createdAt = System.currentTimeMillis(),
                nextAttemptAt = 0L,
            ),
        )
    }

    /* ------------------------------------------------------------- reading */

    fun observeDay(dayKey: String = TimeUtils.todayKey()): Flow<DailyActivity?> =
        activityDao.observeDay(dayKey).map { it?.toDomain() }

    fun observeToday(goalFallback: Int = 8_000): Flow<DailyActivity> =
        activityDao.observeDay(TimeUtils.todayKey()).map { entity ->
            entity?.toDomain() ?: DailyActivity.empty(LocalDate.now(), goalFallback)
        }

    fun observeDays(from: LocalDate, to: LocalDate): Flow<List<DailyActivity>> =
        activityDao.observeDays(from.toString(), to.toString()).map { list -> list.map { it.toDomain() } }

    fun observeLastNDays(days: Int): Flow<List<DailyActivity>> {
        val list = TimeUtils.lastNDays(days)
        return observeDays(list.first(), list.last())
    }

    suspend fun getDay(dayKey: String): DailyActivity? = activityDao.getDay(dayKey)?.toDomain()

    suspend fun recentDays(limit: Int = 28): List<DailyActivity> =
        activityDao.recentDays(limit).map { it.toDomain() }

    suspend fun stepsForDay(dayKey: String, source: String = SOURCE_SENSOR): Int =
        activityDao.stepsForDay(dayKey, source)

    /* --------------------------------------------------------------- syncing */

    /** Uploads changed days; returns the number of days the server accepted. */
    suspend fun syncWithServer(maxDays: Int = 14): Result<Int> = runCatching {
        if (!networkMonitor.isCurrentlyOnline() || !sessionStore.isSignedIn()) {
            return@runCatching 0
        }
        val pending = activityDao.unsyncedDays(maxDays)
        if (pending.isEmpty()) return@runCatching 0

        val uploads = pending.map { day ->
            val buckets = activityDao.getSamples(day.dayKey)
                .filter { it.source != SOURCE_HEALTH_CONNECT }
                .map { StepBucketDto(it.hourOfDay, it.steps, it.distanceMeters, it.calories, it.activeSeconds) }
            ActivityDayUpload(
                dayKey = day.dayKey,
                steps = day.steps,
                distanceMeters = day.distanceMeters,
                calories = day.calories,
                activeMinutes = day.activeMinutes,
                source = "DEVICE",
                zoneOffsetMinutes = TimeUtils.zone().rules.getOffset(java.time.Instant.now()).totalSeconds / 60,
                buckets = buckets,
            )
        }

        val response = api.syncActivity(ActivitySyncRequest(uploads))
        val now = System.currentTimeMillis()
        response.verifiedSteps.forEach { (dayKey, verified) ->
            activityDao.updateVerifiedSteps(dayKey, verified, now)
        }
        pending.forEach { activityDao.markDaySynced(it.dayKey, now) }
        response.acceptedDays
    }.mapToVTrackError()
}
