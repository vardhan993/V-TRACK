package com.vtrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vtrack.app.data.local.entity.ExerciseEntity
import com.vtrack.app.data.local.entity.RoutineEntity
import com.vtrack.app.data.local.entity.RoutineItemEntity
import com.vtrack.app.data.local.entity.StrengthSetEntity
import com.vtrack.app.data.local.entity.WorkoutPointEntity
import com.vtrack.app.data.local.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    /* -------- sessions -------- */

    @Upsert
    suspend fun upsertSession(session: WorkoutSessionEntity)

    @Query("SELECT * FROM workout_session WHERE id = :id")
    suspend fun getSession(id: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_session WHERE id = :id")
    fun observeSession(id: String): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeSession(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveSession(): Flow<WorkoutSessionEntity?>

    @Query("SELECT COUNT(*) FROM workout_session WHERE endedAt IS NOT NULL AND startedAt >= :since")
    suspend fun completedSince(since: Long): Int

    @Query("SELECT * FROM workout_session WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_session WHERE endedAt IS NOT NULL AND startedAt BETWEEN :from AND :to ORDER BY startedAt DESC")
    fun observeSessionsBetween(from: Long, to: Long): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_session WHERE endedAt IS NOT NULL AND startedAt BETWEEN :from AND :to ORDER BY startedAt DESC")
    suspend fun getSessionsBetween(from: Long, to: Long): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_session WHERE endedAt IS NOT NULL AND activityType = :activityType ORDER BY startedAt DESC LIMIT :limit")
    suspend fun sessionsOfType(activityType: String, limit: Int): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_session WHERE syncState != 'SYNCED' AND endedAt IS NOT NULL ORDER BY startedAt LIMIT :limit")
    suspend fun pendingSyncSessions(limit: Int): List<WorkoutSessionEntity>

    @Query("UPDATE workout_session SET syncState = :state, syncedAt = :at, serverId = :serverId WHERE id = :id")
    suspend fun markSessionSynced(id: String, state: String, at: Long, serverId: String?)

    @Query("UPDATE workout_session SET notes = :notes, perceivedEffort = :effort WHERE id = :id")
    suspend fun updateSessionNotes(id: String, notes: String?, effort: Int?)

    @Query("DELETE FROM workout_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT COUNT(*) FROM workout_session WHERE endedAt IS NOT NULL")
    fun observeCompletedWorkoutCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(calories), 0) FROM workout_session WHERE endedAt IS NOT NULL AND startedAt BETWEEN :from AND :to")
    suspend fun caloriesBetween(from: Long, to: Long): Double

    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM workout_session WHERE endedAt IS NOT NULL AND startedAt BETWEEN :from AND :to")
    suspend fun distanceBetween(from: Long, to: Long): Double

    @Query("SELECT COUNT(DISTINCT dayKey) FROM (SELECT date(startedAt / 1000, 'unixepoch', 'localtime') AS dayKey FROM workout_session WHERE endedAt IS NOT NULL)")
    suspend fun distinctWorkoutDays(): Int

    /* -------- GPS points -------- */

    @Upsert
    suspend fun insertPoints(points: List<WorkoutPointEntity>)

    @Upsert
    suspend fun insertPoint(point: WorkoutPointEntity)

    @Query("SELECT * FROM workout_point WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun getPoints(sessionId: String): List<WorkoutPointEntity>

    @Query("SELECT * FROM workout_point WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT 1")
    suspend fun lastPoint(sessionId: String): WorkoutPointEntity?

    @Query("SELECT COUNT(*) FROM workout_point WHERE sessionId = :sessionId")
    suspend fun pointCount(sessionId: String): Int

    @Query("DELETE FROM workout_point WHERE sessionId = :sessionId")
    suspend fun deletePoints(sessionId: String)

    /* -------- strength sets -------- */

    @Upsert
    suspend fun upsertSet(set: StrengthSetEntity)

    @Query("SELECT * FROM strength_set WHERE sessionId = :sessionId ORDER BY setIndex")
    fun observeSets(sessionId: String): Flow<List<StrengthSetEntity>>

    @Query("SELECT * FROM strength_set WHERE sessionId = :sessionId ORDER BY setIndex")
    suspend fun getSets(sessionId: String): List<StrengthSetEntity>

    @Query("SELECT * FROM strength_set WHERE exerciseId = :exerciseId ORDER BY completedAt DESC")
    suspend fun setsForExercise(exerciseId: String): List<StrengthSetEntity>

    @Query("SELECT * FROM strength_set ORDER BY completedAt DESC LIMIT :limit")
    suspend fun recentSets(limit: Int): List<StrengthSetEntity>

    @Query("SELECT MAX(weightKg) FROM strength_set WHERE exerciseId = :exerciseId AND isWarmup = 0")
    suspend fun bestWeight(exerciseId: String): Double?

    @Query("SELECT MAX(reps) FROM strength_set WHERE exerciseId = :exerciseId")
    suspend fun bestReps(exerciseId: String): Int?

    @Query("SELECT COUNT(*) FROM strength_set")
    suspend fun totalSets(): Int

    @Query("DELETE FROM strength_set WHERE id = :id")
    suspend fun deleteSet(id: String)

    /* -------- exercise library -------- */

    @Upsert
    suspend fun upsertExercises(exercises: List<ExerciseEntity>)

    @Upsert
    suspend fun upsertExercise(exercise: ExerciseEntity)

    @Query("SELECT COUNT(*) FROM exercise")
    suspend fun exerciseCount(): Int

    @Query("SELECT * FROM exercise ORDER BY name")
    fun observeExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE category = :category ORDER BY name")
    fun observeExercisesByCategory(category: String): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun getExercise(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercise WHERE name LIKE '%' || :query || '%' ORDER BY LENGTH(name), name LIMIT 25")
    suspend fun searchExercises(query: String): List<ExerciseEntity>

    @Query("DELETE FROM exercise WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustomExercise(id: String)

    /* -------- routines -------- */

    @Upsert
    suspend fun upsertRoutine(routine: RoutineEntity)

    @Query("SELECT * FROM routine ORDER BY updatedAt DESC")
    fun observeRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routine WHERE id = :id")
    suspend fun getRoutine(id: String): RoutineEntity?

    @Query("DELETE FROM routine WHERE id = :id")
    suspend fun deleteRoutine(id: String)

    @Upsert
    suspend fun upsertRoutineItems(items: List<RoutineItemEntity>)

    @Query("SELECT * FROM routine_item WHERE routineId = :routineId ORDER BY position")
    fun observeRoutineItems(routineId: String): Flow<List<RoutineItemEntity>>

    @Query("SELECT * FROM routine_item WHERE routineId = :routineId ORDER BY position")
    suspend fun getRoutineItems(routineId: String): List<RoutineItemEntity>

    @Query("DELETE FROM routine_item WHERE routineId = :routineId")
    suspend fun clearRoutineItems(routineId: String)
}
