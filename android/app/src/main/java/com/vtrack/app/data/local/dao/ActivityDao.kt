package com.vtrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vtrack.app.data.local.entity.ActivityDayEntity
import com.vtrack.app.data.local.entity.StepSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    /* -------- hourly step buckets -------- */

    @Query("SELECT * FROM step_sample WHERE dayKey = :dayKey AND hourOfDay = :hour AND source = :source")
    suspend fun findSample(dayKey: String, hour: Int, source: String): StepSampleEntity?

    @Upsert
    suspend fun upsertSample(sample: StepSampleEntity)

    @Query("SELECT * FROM step_sample WHERE dayKey = :dayKey ORDER BY hourOfDay")
    fun observeSamples(dayKey: String): Flow<List<StepSampleEntity>>

    @Query("SELECT * FROM step_sample WHERE dayKey = :dayKey ORDER BY hourOfDay")
    suspend fun getSamples(dayKey: String): List<StepSampleEntity>

    @Query("SELECT COALESCE(SUM(steps), 0) FROM step_sample WHERE dayKey = :dayKey AND source = :source")
    suspend fun stepsForDay(dayKey: String, source: String): Int

    @Query("SELECT * FROM step_sample WHERE syncedAt IS NULL AND dayKey <= :todayKey ORDER BY dayKey, hourOfDay LIMIT :limit")
    suspend fun unsyncedSamples(todayKey: String, limit: Int): List<StepSampleEntity>

    @Query("UPDATE step_sample SET syncedAt = :at WHERE dayKey = :dayKey")
    suspend fun markSamplesSynced(dayKey: String, at: Long)

    @Query("SELECT * FROM step_sample WHERE dayKey < :beforeKey")
    suspend fun samplesBefore(beforeKey: String): List<StepSampleEntity>

    @Query("DELETE FROM step_sample WHERE dayKey = :dayKey")
    suspend fun deleteSamplesForDay(dayKey: String)

    /* -------- daily aggregates -------- */

    @Query("SELECT * FROM activity_day WHERE dayKey = :dayKey")
    fun observeDay(dayKey: String): Flow<ActivityDayEntity?>

    @Query("SELECT * FROM activity_day WHERE dayKey = :dayKey")
    suspend fun getDay(dayKey: String): ActivityDayEntity?

    @Query("SELECT * FROM activity_day WHERE dayKey BETWEEN :fromKey AND :toKey ORDER BY dayKey")
    fun observeDays(fromKey: String, toKey: String): Flow<List<ActivityDayEntity>>

    @Query("SELECT * FROM activity_day WHERE dayKey BETWEEN :fromKey AND :toKey ORDER BY dayKey")
    suspend fun getDays(fromKey: String, toKey: String): List<ActivityDayEntity>

    @Query("SELECT * FROM activity_day ORDER BY dayKey DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<ActivityDayEntity>

    @Query("SELECT * FROM activity_day WHERE syncedAt IS NULL ORDER BY dayKey LIMIT :limit")
    suspend fun unsyncedDays(limit: Int): List<ActivityDayEntity>

    @Query("UPDATE activity_day SET syncedAt = :at WHERE dayKey = :dayKey")
    suspend fun markDaySynced(dayKey: String, at: Long)

    @Query("UPDATE activity_day SET verifiedSteps = :verifiedSteps, updatedAt = :now WHERE dayKey = :dayKey")
    suspend fun updateVerifiedSteps(dayKey: String, verifiedSteps: Int, now: Long)

    @Upsert
    suspend fun upsertDay(day: ActivityDayEntity)

    @Query("SELECT COUNT(*) FROM activity_day")
    suspend fun countDays(): Int

    @Query("SELECT MAX(steps) FROM activity_day")
    suspend fun bestStepDay(): Int?
}
