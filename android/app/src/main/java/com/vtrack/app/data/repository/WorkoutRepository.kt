package com.vtrack.app.data.repository

import com.vtrack.app.core.error.mapToVTrackError
import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.local.Mappers.toDomain
import com.vtrack.app.data.local.Mappers.toEntity
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.SyncDao
import com.vtrack.app.data.local.dao.WorkoutDao
import com.vtrack.app.data.local.entity.RoutineEntity
import com.vtrack.app.data.local.entity.RoutineItemEntity
import com.vtrack.app.data.local.entity.StrengthSetEntity
import com.vtrack.app.data.local.entity.SyncQueueEntity
import com.vtrack.app.data.local.entity.WorkoutPointEntity
import com.vtrack.app.data.remote.VTrackApi
import com.vtrack.app.data.remote.dto.StrengthSetUpload
import com.vtrack.app.data.remote.dto.WorkoutUpload
import com.vtrack.app.domain.engine.CalorieCalculator
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.Exercise
import com.vtrack.app.domain.model.GpsPoint
import com.vtrack.app.domain.model.PersonalRecord
import com.vtrack.app.domain.model.Routine
import com.vtrack.app.domain.model.StrengthSet
import com.vtrack.app.domain.model.SyncState
import com.vtrack.app.domain.model.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Workout lifecycle and history.
 *
 * Local-first: a session is written to Room the moment it starts and survives process
 * death, being killed by the OS, or a device reboot (the row simply stays open and can be
 * resumed). The server copy is created later by the sync engine using the same
 * client-generated id, which makes the upload idempotent.
 */
@Singleton
class WorkoutRepository @Inject constructor(
    private val workoutDao: WorkoutDao,
    private val profileDao: ProfileDao,
    private val syncDao: SyncDao,
    private val api: VTrackApi,
    private val networkMonitor: NetworkMonitor,
    private val sessionStore: SessionStore,
) {

    /* ------------------------------------------------------------- lifecycle */

    suspend fun startSession(
        activityType: ActivityType,
        indoor: Boolean,
        title: String? = null,
        now: Long = System.currentTimeMillis(),
    ): WorkoutSession {
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            activityType = activityType,
            title = title,
            startedAt = now,
            endedAt = null,
            durationSeconds = 0,
            distanceMeters = 0.0,
            calories = 0.0,
            avgPaceSecPerKm = 0.0,
            avgSpeedKmh = 0.0,
            steps = 0,
            elevationGainMeters = 0.0,
            indoor = indoor || !activityType.usesGps,
            manual = false,
            autoTracked = false,
            notes = null,
            perceivedEffort = null,
            syncState = SyncState.PENDING,
        )
        workoutDao.upsertSession(session.toEntity())
        return session
    }

    /** Persists progress during a live session so nothing is lost if the app is killed. */
    suspend fun saveProgress(session: WorkoutSession) {
        val existing = workoutDao.getSession(session.id)
        workoutDao.upsertSession(session.copy(syncState = SyncState.PENDING).toEntity(existing))
    }

    suspend fun appendPoint(sessionId: String, point: GpsPoint) {
        val sequence = workoutDao.pointCount(sessionId)
        workoutDao.insertPoint(
            WorkoutPointEntity(
                sessionId = sessionId,
                sequence = sequence,
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = point.altitude,
                accuracyMeters = point.accuracyMeters,
                speedMps = point.speedMps,
                recordedAt = point.recordedAt,
            ),
        )
    }

    /**
     * Completes a session, recomputing derived metrics from the stored data (never from
     * values passed in by the UI, so a summary can never disagree with the record).
     */
    suspend fun finishSession(
        sessionId: String,
        notes: String? = null,
        perceivedEffort: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): WorkoutSession? {
        val existing = workoutDao.getSession(sessionId) ?: return null
        val points = workoutDao.getPoints(sessionId)
        val profile = profileDao.getProfile()
        val duration = ((now - existing.startedAt) / 1000).coerceAtLeast(0)

        val distance = if (points.size >= 2) {
            maxOf(polylineDistance(points), existing.distanceMeters)
        } else {
            existing.distanceMeters
        }
        val elevation = elevationGain(points)
        val activity = ActivityType.fromStorage(existing.activityType)

        val calories = if (existing.calories > 0) {
            existing.calories
        } else {
            CalorieCalculator.fromActivity(
                activity = activity,
                weightKg = CalorieCalculator.effectiveWeight(profile?.weightKg),
                durationSeconds = duration,
            )
        }

        val pacePerKm = if (distance > 50) duration / (distance / 1000.0) else 0.0
        val speedKmh = if (duration > 0) (distance / 1000.0) / (duration / 3600.0) else 0.0

        val finished = WorkoutSession(
            id = existing.id,
            activityType = activity,
            title = existing.title,
            startedAt = existing.startedAt,
            endedAt = now,
            durationSeconds = duration,
            distanceMeters = distance,
            calories = calories,
            avgPaceSecPerKm = pacePerKm,
            avgSpeedKmh = speedKmh,
            steps = existing.steps,
            elevationGainMeters = elevation,
            indoor = existing.indoor,
            manual = existing.manual,
            autoTracked = existing.autoTracked,
            notes = notes ?: existing.notes,
            perceivedEffort = perceivedEffort ?: existing.perceivedEffort,
            syncState = SyncState.PENDING,
        )
        workoutDao.upsertSession(finished.toEntity(existing))
        syncDao.enqueue(
            SyncQueueEntity(
                entityType = "WORKOUT",
                entityId = finished.id,
                operation = "UPSERT",
                createdAt = now,
                nextAttemptAt = 0L,
            ),
        )
        return finished
    }

    /** Manual logging for activities the phone cannot measure (gym machines, classes). */
    suspend fun logManualSession(
        activityType: ActivityType,
        startedAt: Long,
        durationSeconds: Long,
        distanceMeters: Double = 0.0,
        steps: Int = 0,
        notes: String? = null,
        perceivedEffort: Int? = null,
        indoor: Boolean = true,
    ): WorkoutSession {
        val profile = profileDao.getProfile()
        val calories = CalorieCalculator.fromActivity(
            activity = activityType,
            weightKg = CalorieCalculator.effectiveWeight(profile?.weightKg),
            durationSeconds = durationSeconds,
        )
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            activityType = activityType,
            title = null,
            startedAt = startedAt,
            endedAt = startedAt + durationSeconds * 1000,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            calories = calories,
            avgPaceSecPerKm = if (distanceMeters > 50) durationSeconds / (distanceMeters / 1000.0) else 0.0,
            avgSpeedKmh = if (durationSeconds > 0) (distanceMeters / 1000.0) / (durationSeconds / 3600.0) else 0.0,
            steps = steps,
            elevationGainMeters = 0.0,
            indoor = indoor,
            manual = true,
            autoTracked = false,
            notes = notes,
            perceivedEffort = perceivedEffort,
            syncState = SyncState.PENDING,
        )
        workoutDao.upsertSession(session.toEntity())
        syncDao.enqueue(
            SyncQueueEntity(
                entityType = "WORKOUT",
                entityId = session.id,
                operation = "UPSERT",
                createdAt = System.currentTimeMillis(),
                nextAttemptAt = 0L,
            ),
        )
        return session
    }

    suspend fun deleteSession(sessionId: String) {
        workoutDao.deletePoints(sessionId)
        workoutDao.deleteSession(sessionId)
        syncDao.enqueue(
            SyncQueueEntity(
                entityType = "WORKOUT",
                entityId = sessionId,
                operation = "DELETE",
                createdAt = System.currentTimeMillis(),
                nextAttemptAt = 0L,
            ),
        )
    }

    suspend fun updateNotes(sessionId: String, notes: String?, effort: Int?) {
        workoutDao.updateSessionNotes(sessionId, notes, effort)
        syncDao.enqueue(
            SyncQueueEntity(
                entityType = "WORKOUT_NOTES",
                entityId = sessionId,
                operation = "PATCH",
                createdAt = System.currentTimeMillis(),
                nextAttemptAt = 0L,
            ),
        )
    }

    /* --------------------------------------------------------------- reading */

    fun observeHistory(limit: Int = 100): Flow<List<WorkoutSession>> =
        workoutDao.observeHistory(limit).map { list -> list.map { it.toDomain() } }

    fun observeSession(id: String): Flow<WorkoutSession?> =
        workoutDao.observeSession(id).map { it?.toDomain() }

    fun observeActiveSession(): Flow<WorkoutSession?> =
        workoutDao.observeActiveSession().map { it?.toDomain() }

    suspend fun getSession(id: String): WorkoutSession? = workoutDao.getSession(id)?.toDomain()

    suspend fun sessionsBetween(from: Long, to: Long): List<WorkoutSession> =
        workoutDao.getSessionsBetween(from, to).map { it.toDomain() }

    suspend fun getPoints(sessionId: String): List<GpsPoint> =
        workoutDao.getPoints(sessionId).map { it.toDomain() }

    suspend fun completedCountSince(since: Long): Int = workoutDao.completedSince(since)

    fun observeCompletedCount(): Flow<Int> = workoutDao.observeCompletedWorkoutCount()

    suspend fun totalSets(): Int = workoutDao.totalSets()

    suspend fun totalDistanceMetersBetween(from: Long, to: Long): Double =
        workoutDao.distanceBetween(from, to)

    suspend fun caloriesBetween(from: Long, to: Long): Double = workoutDao.caloriesBetween(from, to)

    suspend fun recentSessionsOfType(activityType: ActivityType, limit: Int = 20): List<WorkoutSession> =
        workoutDao.sessionsOfType(activityType.name, limit).map { it.toDomain() }

    /** Heaviest weight recorded for an exercise, used to show "last time" hints. */
    suspend fun bestWeightKg(exerciseId: String): Double? = workoutDao.bestWeight(exerciseId)

    suspend fun bestReps(exerciseId: String): Int? = workoutDao.bestReps(exerciseId)

    /* --------------------------------------------------------------- sets */

    suspend fun addSet(
        sessionId: String,
        exerciseId: String,
        reps: Int?,
        weightKg: Double?,
        durationSeconds: Int? = null,
        restSeconds: Int? = null,
        rpe: Int? = null,
        isWarmup: Boolean = false,
    ): StrengthSet {
        val setIndex = workoutDao.getSets(sessionId).count { it.exerciseId == exerciseId }
        val set = StrengthSet(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            exerciseId = exerciseId,
            setIndex = setIndex,
            reps = reps,
            weightKg = weightKg,
            durationSeconds = durationSeconds,
            restSeconds = restSeconds,
            rpe = rpe,
            isWarmup = isWarmup,
            completedAt = System.currentTimeMillis(),
        )
        workoutDao.upsertSet(set.toEntity())
        return set
    }

    suspend fun deleteSet(setId: String) = workoutDao.deleteSet(setId)

    suspend fun setsForExerciseOnce(exerciseId: String): List<StrengthSet> =
        workoutDao.setsForExercise(exerciseId).map { it.toDomain() }

    fun observeSets(sessionId: String): Flow<List<StrengthSet>> =
        workoutDao.observeSets(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun getSets(sessionId: String): List<StrengthSet> =
        workoutDao.getSets(sessionId).map { it.toDomain() }

    /** Personal records: heaviest completed set and best rep count per exercise. */
    suspend fun personalRecords(limit: Int = 12): List<PersonalRecord> {
        val recentSets = workoutDao.recentSets(400)
        val grouped = recentSets.groupBy { it.exerciseId }
        return grouped.mapNotNull { (exerciseId, sets) ->
            val best = sets.maxByOrNull { it.weightKg ?: 0.0 }
            val exercise = workoutDao.getExercise(exerciseId)
            PersonalRecord(
                exerciseId = exerciseId,
                exerciseName = exercise?.name ?: exerciseId,
                bestWeightKg = best?.weightKg,
                bestReps = sets.mapNotNull { it.reps }.maxOrNull(),
                achievedAt = sets.maxOf { it.completedAt },
            )
        }
            .sortedByDescending { it.bestWeightKg ?: 0.0 }
            .take(limit)
    }

    /* ------------------------------------------------------------- catalog */

    fun observeExercises(): Flow<List<Exercise>> =
        workoutDao.observeExercises().map { list -> list.map { it.toDomain() } }

    fun observeExercisesByCategory(category: String): Flow<List<Exercise>> =
        workoutDao.observeExercisesByCategory(category).map { list -> list.map { it.toDomain() } }

    suspend fun getExercise(id: String): Exercise? = workoutDao.getExercise(id)?.toDomain()

    suspend fun searchExercises(query: String): List<Exercise> =
        workoutDao.searchExercises(query.trim()).map { it.toDomain() }

    suspend fun lastPerformance(exerciseId: String): StrengthSet? =
        workoutDao.setsForExercise(exerciseId).firstOrNull()?.toDomain()

    /* ------------------------------------------------------------ routines */

    fun observeRoutines(): Flow<List<Routine>> =
        workoutDao.observeRoutines().map { list ->
            list.map { routine ->
                routine.toDomain(workoutDao.getRoutineItems(routine.id))
            }
        }

    suspend fun saveRoutine(routine: Routine) {
        val now = System.currentTimeMillis()
        workoutDao.upsertRoutine(
            RoutineEntity(
                id = routine.id.ifBlank { UUID.randomUUID().toString() },
                name = routine.name,
                description = routine.description,
                activityType = routine.activityType.name,
                scheduledWeekdays = routine.scheduledWeekdays.joinToString(","),
                createdAt = now,
                updatedAt = now,
            ),
        )
        workoutDao.clearRoutineItems(routine.id)
        workoutDao.upsertRoutineItems(
            routine.items.map { item ->
                RoutineItemEntity(
                    routineId = routine.id,
                    position = item.position,
                    exerciseId = item.exerciseId,
                    label = item.label,
                    sets = item.sets,
                    reps = item.reps,
                    durationSeconds = item.durationSeconds,
                    restSeconds = item.restSeconds,
                )
            },
        )
    }

    suspend fun deleteRoutine(routineId: String) {
        workoutDao.clearRoutineItems(routineId)
        workoutDao.deleteRoutine(routineId)
    }

    /* --------------------------------------------------------------- sync */

    /**
     * Uploads finished sessions (and their sets). The server upserts on `clientId`, so a
     * retry after a timeout can never duplicate a workout or its reward.
     */
    suspend fun syncWithServer(maxSessions: Int = 10): Result<Int> = runCatching {
        if (!networkMonitor.isCurrentlyOnline() || !sessionStore.isSignedIn()) return@runCatching 0
        val pending = workoutDao.pendingSyncSessions(maxSessions)
        if (pending.isEmpty()) return@runCatching 0

        val uploads = pending.map { entity ->
            val sets = workoutDao.getSets(entity.id).map { set ->
                StrengthSetUpload(
                    clientId = set.id,
                    exerciseId = set.exerciseId,
                    setIndex = set.setIndex,
                    reps = set.reps,
                    weightKg = set.weightKg,
                    durationSeconds = set.durationSeconds,
                    restSeconds = set.restSeconds,
                    rpe = set.rpe,
                    isWarmup = set.isWarmup,
                    completedAt = set.completedAt,
                )
            }
            WorkoutUpload(
                clientId = entity.id,
                activityType = entity.activityType,
                title = entity.title,
                startedAt = entity.startedAt,
                endedAt = entity.endedAt ?: entity.startedAt,
                durationSeconds = entity.durationSeconds,
                distanceMeters = entity.distanceMeters,
                calories = entity.calories,
                avgPaceSecPerKm = entity.avgPaceSecPerKm,
                avgSpeedKmh = entity.avgSpeedKmh,
                steps = entity.steps,
                elevationGainMeters = entity.elevationGainMeters,
                indoor = entity.indoor,
                manual = entity.manual,
                notes = entity.notes,
                perceivedEffort = entity.perceivedEffort,
                sets = sets,
            )
        }

        val response = api.syncWorkouts(uploads)
        val now = System.currentTimeMillis()
        val serverIds = response.workouts.associate { it.clientId to it.id }
        pending.forEach { entity ->
            workoutDao.markSessionSynced(
                id = entity.id,
                state = SyncState.SYNCED.name,
                at = now,
                serverId = serverIds[entity.id],
            )
            // Notes/effort travel inside the upload, so their outbox rows are done too.
            syncDao.removeByKey("WORKOUT", entity.id, "UPSERT")
            syncDao.removeByKey("WORKOUT_NOTES", entity.id, "PATCH")
        }
        response.accepted
    }.mapToVTrackError()

    /* ------------------------------------------------------------ geometry */

    /** Haversine sum over the recorded track — the same maths the summary screen shows. */
    fun polylineDistance(points: List<WorkoutPointEntity>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversine(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude,
            )
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Only positive altitude deltas from points with decent accuracy are accumulated. */
    private fun elevationGain(points: List<WorkoutPointEntity>): Double {
        if (points.size < 3) return 0.0
        var gain = 0.0
        var previous = points.first().altitude
        points.drop(1).forEach { point ->
            if (point.accuracyMeters in 0f..35f && point.altitude > previous) {
                val delta = point.altitude - previous
                if (delta in 0.3..25.0) gain += delta
            }
            previous = point.altitude
        }
        return gain
    }

    /** Convenience used by the live screen to expose the local day of a session. */
    fun dayKeyOf(session: WorkoutSession): String = TimeUtils.dayKey(session.startedAt)
}
