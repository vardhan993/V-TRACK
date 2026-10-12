package com.vtrack.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.vtrack.app.data.local.entity.AchievementEntity
import com.vtrack.app.data.local.entity.ChallengeEntity
import com.vtrack.app.data.local.entity.ChallengeProgressEntity
import com.vtrack.app.data.local.entity.CoachPlanEntity
import com.vtrack.app.data.local.entity.CoinTransactionEntity
import com.vtrack.app.data.local.entity.RedemptionEntity
import com.vtrack.app.data.local.entity.RewardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RewardsDao {

    /* -------- coin ledger -------- */

    @Query("SELECT * FROM coin_transaction ORDER BY createdAt DESC LIMIT :limit")
    fun observeTransactions(limit: Int): Flow<List<CoinTransactionEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM coin_transaction WHERE status = 'CONFIRMED'")
    fun observeConfirmedBalance(): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM coin_transaction WHERE status IN ('PENDING', 'LOCAL_ESTIMATE')")
    fun observeUnconfirmedBalance(): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM coin_transaction WHERE status = 'CONFIRMED' AND type IN ('STEP_REWARD', 'WORKOUT_REWARD') AND createdAt >= :since")
    suspend fun earnedFromActivitySince(since: Long): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM coin_transaction WHERE status IN ('CONFIRMED', 'PENDING') AND type = 'STEP_REWARD' AND createdAt >= :dayStart")
    suspend fun stepRewardsToday(dayStart: Long): Long

    @Query("SELECT * FROM coin_transaction WHERE clientEventId = :clientEventId")
    suspend fun findTransaction(clientEventId: String): CoinTransactionEntity?

    @Upsert
    suspend fun upsertTransaction(transaction: CoinTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionIfAbsent(transaction: CoinTransactionEntity): Long

    @Query("SELECT * FROM coin_transaction WHERE status IN ('PENDING', 'LOCAL_ESTIMATE') ORDER BY createdAt LIMIT :limit")
    suspend fun unconfirmedTransactions(limit: Int): List<CoinTransactionEntity>

    @Query("UPDATE coin_transaction SET status = :status, serverId = :serverId, balanceAfter = :balanceAfter, rejectionReason = :reason WHERE clientEventId = :clientEventId")
    suspend fun resolveTransaction(
        clientEventId: String,
        status: String,
        serverId: String?,
        balanceAfter: Long?,
        reason: String?,
    )

    @Query("DELETE FROM coin_transaction WHERE status = 'LOCAL_ESTIMATE' AND createdAt < :before")
    suspend fun pruneEstimates(before: Long)

    @Query("SELECT COALESCE(SUM(CAST(amount AS INTEGER)), 0) FROM coin_transaction WHERE status = 'CONFIRMED'")
    suspend fun confirmedBalance(): Long

    /* -------- challenges -------- */

    @Upsert
    suspend fun upsertChallenges(challenges: List<ChallengeEntity>)

    @Query("SELECT * FROM challenge WHERE isActive = 1 ORDER BY period, targetValue")
    fun observeChallenges(): Flow<List<ChallengeEntity>>

    @Query("SELECT * FROM challenge WHERE id = :id")
    suspend fun getChallenge(id: String): ChallengeEntity?

    @Query("SELECT * FROM challenge WHERE code = :code LIMIT 1")
    suspend fun getChallengeByCode(code: String): ChallengeEntity?

    @Query("DELETE FROM challenge")
    suspend fun clearChallenges()

    @Upsert
    suspend fun upsertChallengeProgress(progress: ChallengeProgressEntity)

    @Query("SELECT * FROM challenge_progress WHERE periodKey = :periodKey")
    fun observeProgressForPeriod(periodKey: String): Flow<List<ChallengeProgressEntity>>

    @Query("SELECT * FROM challenge_progress WHERE challengeId = :challengeId AND periodKey = :periodKey")
    suspend fun getProgress(challengeId: String, periodKey: String): ChallengeProgressEntity?

    @Query("SELECT * FROM challenge_progress WHERE completed = 1 AND claimed = 0")
    suspend fun claimableProgress(): List<ChallengeProgressEntity>

    /* -------- achievements -------- */

    @Upsert
    suspend fun upsertAchievements(achievements: List<AchievementEntity>)

    @Query("SELECT * FROM achievement ORDER BY category, threshold")
    fun observeAchievements(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievement WHERE code = :code")
    suspend fun getAchievement(code: String): AchievementEntity?

    @Query("UPDATE achievement SET progress = :progress, unlockedAt = COALESCE(unlockedAt, :now), updatedAt = :now WHERE code = :code")
    suspend fun updateAchievement(code: String, progress: Double, now: Long)

    /* -------- reward catalog + redemptions -------- */

    @Upsert
    suspend fun upsertRewards(rewards: List<RewardEntity>)

    @Query("SELECT * FROM reward WHERE isActive = 1 ORDER BY costCoins")
    fun observeRewards(): Flow<List<RewardEntity>>

    @Query("SELECT * FROM redemption ORDER BY requestedAt DESC")
    fun observeRedemptions(): Flow<List<RedemptionEntity>>

    @Upsert
    suspend fun upsertRedemption(redemption: RedemptionEntity)

    @Query("SELECT COUNT(*) FROM redemption WHERE status IN ('REQUESTED', 'APPROVED')")
    suspend fun openRedemptionCount(): Int

    /* -------- coach plans -------- */

    @Upsert
    suspend fun upsertCoachPlan(plan: CoachPlanEntity)

    @Query("SELECT * FROM coach_plan WHERE weekStartDayKey = :weekStart ORDER BY createdAt DESC LIMIT 1")
    fun observePlanForWeek(weekStart: String): Flow<CoachPlanEntity?>

    @Query("SELECT * FROM coach_plan ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestPlan(): CoachPlanEntity?
}
