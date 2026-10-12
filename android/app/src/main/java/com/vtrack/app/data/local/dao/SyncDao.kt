package com.vtrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vtrack.app.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    /** Oldest due items first so activity is uploaded in chronological order. */
    @Query("SELECT * FROM sync_queue WHERE nextAttemptAt <= :now ORDER BY createdAt LIMIT :limit")
    suspend fun dueItems(now: Long, limit: Int): List<SyncQueueEntity>

    /**
     * The unique index on (entityType, entityId, operation) turns re-enqueues into
     * updates, so a row is never queued twice and retries stay idempotent.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: SyncQueueEntity)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("DELETE FROM sync_queue WHERE entityType = :entityType AND entityId = :entityId AND operation = :operation")
    suspend fun removeByKey(entityType: String, entityId: String, operation: String)

    @Query("UPDATE sync_queue SET attempts = attempts + 1, lastError = :error, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun backoff(id: Long, error: String, nextAttemptAt: Long)

    @Query("DELETE FROM sync_queue WHERE attempts >= :maxAttempts")
    suspend fun dropExhausted(maxAttempts: Int)

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observeQueueSize(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun queueSize(): Int

    @Query("SELECT COALESCE(MAX(attempts), 0) FROM sync_queue")
    suspend fun maxAttempts(): Int
}
