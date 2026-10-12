package com.vtrack.app.tracking

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.WorkoutDao
import com.vtrack.app.data.local.entity.ImportedRecordEntity
import com.vtrack.app.data.local.entity.WorkoutSessionEntity
import com.vtrack.app.data.repository.ActivityRepository
import com.vtrack.app.domain.engine.CalorieCalculator
import com.vtrack.app.domain.model.ActivityType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Health Connect integration (optional, off by default).
 *
 * Why it exists: many users already have steps and workouts recorded by another app or a
 * wearable. Reading them makes the dashboard complete instead of knowingly showing a
 * half-empty day.
 *
 * How it stays safe and honest:
 *  - Only read permissions are requested; V-Track never writes to Health Connect.
 *  - Every imported record id is stored in `imported_record`, so repeated imports are
 *    no-ops and a record is never counted twice.
 *  - Imported steps live in their own source bucket, so the device sensor remains the
 *    primary measurement and rewards stay tied to device-verified activity.
 *  - If Health Connect is unavailable (or on Android < 9 / a device without it), every
 *    method degrades to a no-op instead of throwing.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityRepository: ActivityRepository,
    private val activityDao: ActivityDao,
    private val workoutDao: WorkoutDao,
    private val profileDao: ProfileDao,
) {

    /** Permissions V-Track will ask for — read-only, and only what the features need. */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    fun sdkStatus(): SdkStatus = try {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> SdkStatus.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SdkStatus.UPDATE_REQUIRED
            else -> SdkStatus.UNAVAILABLE
        }
    } catch (t: Throwable) {
        SdkStatus.UNAVAILABLE
    }

    fun client(): HealthConnectClient? =
        if (sdkStatus() == SdkStatus.AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }

    suspend fun grantedPermissions(): Set<String> {
        val client = client() ?: return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
    }

    /**
     * Imports the last [days] days of activity. Returns the number of imported records.
     * Imported exercise sessions become auto-tracked workouts so they appear in history.
     */
    suspend fun importRecent(days: Int = 7): ImportOutcome {
        val client = client() ?: return ImportOutcome(available = false)
        val granted = grantedPermissions()
        if (granted.isEmpty()) {
            return ImportOutcome(available = true, permissionMissing = true)
        }

        val zone = TimeUtils.zone()
        var importedRecords = 0
        val today = LocalDate.now(zone)

        for (offset in 0 until days) {
            val day = today.minusDays(offset.toLong())
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()

            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                importedRecords += importSteps(client, day, start, end, zone)
            }
            if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                importedRecords += importSessions(client, start, end, zone)
            }
        }

        // Housekeeping: keep the de-duplication table small.
        profileDao.pruneImportedRecords(
            System.currentTimeMillis() - 400L * 24 * 3600 * 1000,
        )
        return ImportOutcome(available = true, importedRecords = importedRecords)
    }

    private suspend fun importSteps(
        client: HealthConnectClient,
        day: LocalDate,
        start: Instant,
        end: Instant,
        zone: ZoneId,
    ): Int {
        val response = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
        }.getOrNull() ?: return 0

        // Day totals are aggregated once and then apportioned across the hour buckets by
        // step share, which avoids one aggregate query per record.
        val totalSteps = response.records.sumOf { it.count }.coerceAtLeast(1L).toDouble()
        val dayDistance = aggregateDistance(client, start, end)
        val dayCalories = aggregateCalories(client, start, end)

        var imported = 0
        response.records.forEach { record ->
            val recordId = record.metadata.id
            if (profileDao.findImportedRecord(recordId) != null) return@forEach
            val hour = record.startTime.atZone(zone).hour
            val steps = record.count.toInt()
            if (steps <= 0) return@forEach
            val share = steps / totalSteps

            activityRepository.importExternalSteps(
                dayKey = TimeUtils.dayKey(day),
                hour = hour,
                steps = steps,
                distanceMeters = dayDistance * share,
                calories = dayCalories * share,
                activeSeconds = (record.endTime.epochSecond - record.startTime.epochSecond).toInt(),
                source = ActivityRepository.SOURCE_HEALTH_CONNECT,
            )
            profileDao.insertImportedRecord(
                ImportedRecordEntity(
                    recordId = recordId,
                    source = "HEALTH_CONNECT",
                    recordType = "Steps",
                    startTime = record.startTime.toEpochMilli(),
                    importedAt = System.currentTimeMillis(),
                ),
            )
            imported++
        }
        return imported
    }

    private suspend fun aggregateDistance(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): Double {
        val result = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
        }.getOrNull() ?: return 0.0
        return result[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
    }

    private suspend fun aggregateCalories(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ): Double {
        val result = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
        }.getOrNull() ?: return 0.0
        return result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
    }

    private suspend fun importSessions(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        zone: ZoneId,
    ): Int {
        val response = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
        }.getOrNull() ?: return 0

        var imported = 0
        response.records.forEach { session ->
            val recordId = session.metadata.id
            if (profileDao.findImportedRecord(recordId) != null) return@forEach
            val startedAt = session.startTime.toEpochMilli()
            val endedAt = session.endTime.toEpochMilli()
            val durationSeconds = (endedAt - startedAt) / 1000
            if (durationSeconds < 300) return@forEach

            val activityType = mapExerciseType(session.exerciseType)
            val profile = profileDao.getProfile()
            val calories = CalorieCalculator.fromActivity(
                activity = activityType,
                weightKg = CalorieCalculator.effectiveWeight(profile?.weightKg),
                durationSeconds = durationSeconds,
            )
            workoutDao.upsertSession(
                WorkoutSessionEntity(
                    id = "hc.$recordId",
                    activityType = activityType.name,
                    title = session.title ?: "Imported from Health Connect",
                    startedAt = startedAt,
                    endedAt = endedAt,
                    durationSeconds = durationSeconds,
                    distanceMeters = 0.0,
                    calories = calories,
                    avgPaceSecPerKm = 0.0,
                    avgSpeedKmh = 0.0,
                    steps = 0,
                    elevationGainMeters = 0.0,
                    indoor = false,
                    manual = false,
                    autoTracked = true,
                    notes = "Imported from Health Connect",
                    perceivedEffort = null,
                    syncState = com.vtrack.app.domain.model.SyncState.PENDING.name,
                    syncedAt = null,
                    serverId = null,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            profileDao.insertImportedRecord(
                ImportedRecordEntity(
                    recordId = recordId,
                    source = "HEALTH_CONNECT",
                    recordType = "ExerciseSession",
                    startTime = startedAt,
                    importedAt = System.currentTimeMillis(),
                ),
            )
            imported++
        }
        return imported
    }

    /** Health Connect exercise types → V-Track activity types. */
    private fun mapExerciseType(healthConnectType: Int): ActivityType = when (healthConnectType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        -> ActivityType.RUNNING
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ActivityType.WALKING
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        -> ActivityType.CYCLING
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> ActivityType.HIKING
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> ActivityType.ELLIPTICAL
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> ActivityType.STRENGTH
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> ActivityType.YOGA
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> ActivityType.STRETCHING
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> ActivityType.HIIT
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> ActivityType.DANCE
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> ActivityType.ROWING
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
        -> ActivityType.STAIR_CLIMBING
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        -> ActivityType.SWIMMING
        else -> ActivityType.OTHER
    }

    enum class SdkStatus { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

    data class ImportOutcome(
        val available: Boolean,
        val permissionMissing: Boolean = false,
        val importedRecords: Int = 0,
    )
}
