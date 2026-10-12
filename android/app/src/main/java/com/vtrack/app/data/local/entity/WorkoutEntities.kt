package com.vtrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A completed or in-progress workout. `id` is a client-generated UUID that doubles as
 * the idempotency key: the server upserts on it, so a retried sync can never create a
 * duplicate workout (or a duplicate reward).
 */
@Entity(
    tableName = "workout_session",
    indices = [
        Index("startedAt"),
        Index("syncState"),
        Index("activityType"),
    ],
)
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val activityType: String,
    val title: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val calories: Double,
    val avgPaceSecPerKm: Double,
    val avgSpeedKmh: Double,
    val steps: Int,
    val elevationGainMeters: Double,
    val indoor: Boolean,
    val manual: Boolean,
    val autoTracked: Boolean,
    val notes: String?,
    val perceivedEffort: Int?,
    val syncState: String,
    val syncedAt: Long?,
    val serverId: String?,
    val createdAt: Long,
)

/**
 * GPS track point. Privacy: this table stays on-device and is only uploaded when the
 * user has explicitly opted in to route uploads; the API defaults to no route sync.
 */
@Entity(
    tableName = "workout_point",
    primaryKeys = ["sessionId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class WorkoutPointEntity(
    val sessionId: String,
    val sequence: Int,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val recordedAt: Long,
)

/** One logged set of a strength or bodyweight exercise. */
@Entity(
    tableName = "strength_set",
    indices = [Index("sessionId"), Index("exerciseId")],
)
data class StrengthSetEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int?,
    val weightKg: Double?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val restSeconds: Int?,
    val rpe: Int?,
    val isWarmup: Boolean,
    val completedAt: Long,
    val syncState: String,
)

/**
 * Exercise library entry. Bundled with the app (seeded once) so the library works
 * fully offline; users can add their own custom entries.
 */
@Entity(
    tableName = "exercise",
    indices = [Index("category"), Index("primaryMuscle")],
)
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val primaryMuscle: String,
    val equipment: String,
    val difficulty: String,
    val metValue: Double,
    val instructionsJson: String,
    val cuesJson: String,
    val commonMistakesJson: String,
    val defaultSets: Int?,
    val defaultReps: Int?,
    val defaultSeconds: Int?,
    val isCompound: Boolean,
    val isCustom: Boolean,
    val createdAt: Long,
)

/** A user-built routine (ordered exercise list) that can be scheduled per weekday. */
@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val activityType: String,
    /** CSV of ISO weekday numbers (1 = Monday … 7 = Sunday) the user wants reminders on. */
    val scheduledWeekdays: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "routine_item",
    primaryKeys = ["routineId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routineId")],
)
data class RoutineItemEntity(
    val routineId: String,
    val position: Int,
    val exerciseId: String?,
    val label: String,
    val sets: Int?,
    val reps: Int?,
    val durationSeconds: Int?,
    val restSeconds: Int?,
)
