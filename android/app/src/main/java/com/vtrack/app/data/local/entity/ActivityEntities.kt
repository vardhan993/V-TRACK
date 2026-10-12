package com.vtrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Hourly step bucket. Storing hourly buckets instead of every sensor event keeps the
 * database tiny while still allowing the "when were you active" charts and, crucially,
 * letting a reward claim cover a specific, auditable time window.
 *
 * `source` separates device sensors (SENSOR) from Health Connect imports (HEALTH_CONNECT)
 * so the dashboard can display the device's own measurement first.
 */
@Entity(
    tableName = "step_sample",
    primaryKeys = ["dayKey", "hourOfDay", "source"],
    indices = [Index("dayKey"), Index("syncedAt")],
)
data class StepSampleEntity(
    val dayKey: String,
    val hourOfDay: Int,
    val source: String,
    val steps: Int,
    val distanceMeters: Double,
    val calories: Double,
    val activeSeconds: Int,
    val updatedAt: Long,
    val syncedAt: Long?,
)

/**
 * One row per local calendar day — the unit that is charted, rewarded and synced.
 * `steps` is what this device observed; `verifiedSteps` is what the server accepted
 * as reward-eligible. Keeping both is what makes the "estimated vs verified" split honest.
 */
@Entity(tableName = "activity_day", indices = [Index("syncedAt")])
data class ActivityDayEntity(
    @PrimaryKey val dayKey: String,
    val steps: Int,
    val verifiedSteps: Int,
    val distanceMeters: Double,
    val calories: Double,
    val activeMinutes: Int,
    val goalSteps: Int,
    val goalMet: Boolean,
    val firstSampleAt: Long?,
    val lastSampleAt: Long?,
    val updatedAt: Long,
    val syncedAt: Long?,
)

/**
 * Durable outbox for the sync engine. Every mutable record has at most one pending
 * row per (entityType, entityId, operation) thanks to the unique index, which makes
 * retries idempotent and prevents duplicate cloud records.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["entityType", "entityId", "operation"], unique = true),
        Index("nextAttemptAt"),
    ],
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val nextAttemptAt: Long,
)
