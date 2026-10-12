package com.vtrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.vtrack.app.data.local.entity.GoalEntity
import com.vtrack.app.data.local.entity.ImportedRecordEntity
import com.vtrack.app.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM user_profile LIMIT 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun getProfile(): UserProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: UserProfileEntity)

    @Query("UPDATE user_profile SET stepGoal = :steps, updatedAt = :now, syncedAt = NULL")
    suspend fun updateStepGoal(steps: Int, now: Long)

    @Query("UPDATE user_profile SET onboardingComplete = 1, updatedAt = :now, syncedAt = NULL")
    suspend fun markOnboardingComplete(now: Long)

    @Query("UPDATE user_profile SET healthConnectEnabled = :enabled, updatedAt = :now, syncedAt = NULL")
    suspend fun updateHealthConnect(enabled: Boolean, now: Long)

    @Query("DELETE FROM user_profile")
    suspend fun clearProfile()

    /* -------- goals -------- */

    @Query("SELECT * FROM goal")
    fun observeGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal WHERE type = :type")
    suspend fun getGoal(type: String): GoalEntity?

    @Upsert
    suspend fun upsertGoal(goal: GoalEntity)

    @Upsert
    suspend fun upsertGoals(goals: List<GoalEntity>)

    /* -------- Health Connect de-duplication -------- */

    @Query("SELECT recordId FROM imported_record WHERE recordId = :recordId")
    suspend fun findImportedRecord(recordId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImportedRecord(record: ImportedRecordEntity): Long

    @Query("DELETE FROM imported_record WHERE importedAt < :before")
    suspend fun pruneImportedRecords(before: Long)

    @Query("DELETE FROM imported_record")
    suspend fun clearImportedRecords()
}
