package com.vtrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of the server-side user profile. Exactly one row is kept for the
 * signed-in account (id = server user id) and it is the source of truth offline.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val email: String,
    val displayName: String,
    val avatarEmoji: String?,
    val avatarUrl: String?,
    val birthYear: Int?,
    val sex: String?,
    val heightCm: Double?,
    val weightKg: Double?,
    val fitnessLevel: String,
    val primaryGoal: String,
    val preferredActivities: String?, // CSV of ActivityType names
    val weeklyWorkoutTarget: Int,
    val unitSystem: String,
    val stepGoal: Int,
    val onboardingComplete: Boolean,
    val healthConnectEnabled: Boolean,
    /** Privacy: GPS routes are never public. Opt-in only, default false. */
    val shareRoutes: Boolean,
    val shareActivityWithCoach: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val syncedAt: Long?,
)

/** Configurable targets (STEPS, DISTANCE_METERS, ACTIVE_MINUTES, WEEKLY_WORKOUTS, CALORIES). */
@Entity(tableName = "goal", indices = [Index(value = ["type"], unique = true)])
data class GoalEntity(
    @PrimaryKey val type: String,
    val targetValue: Double,
    val period: String, // DAILY | WEEKLY
    val updatedAt: Long,
    val syncedAt: Long?,
)

/**
 * Records imported from Health Connect so the same record is never counted twice
 * (either locally or in a reward claim).
 */
@Entity(tableName = "imported_record", primaryKeys = ["recordId"])
data class ImportedRecordEntity(
    val recordId: String,
    val source: String,
    val recordType: String,
    val startTime: Long,
    val importedAt: Long,
)
