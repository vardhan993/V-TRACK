package com.vtrack.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of the server coin ledger.
 *
 * Status semantics (this split is the whole point of the rewards design):
 *  - LOCAL_ESTIMATE: computed on device, shown as an estimate, never counted as balance.
 *  - PENDING: submitted to the API and awaiting verification.
 *  - CONFIRMED: written by the server; the only status that affects the balance.
 *  - REJECTED: server refused the claim (duplicate, cap reached, unverifiable).
 */
@Entity(
    tableName = "coin_transaction",
    indices = [
        Index(value = ["clientEventId"], unique = true),
        Index("createdAt"),
        Index("status"),
    ],
)
data class CoinTransactionEntity(
    @PrimaryKey val id: String,
    val clientEventId: String,
    val type: String,
    val amount: Long,
    val reason: String,
    val activityRef: String?,
    val status: String,
    val rejectionReason: String?,
    val balanceAfter: Long?,
    val createdAt: Long,
    val serverId: String?,
)

@Entity(tableName = "challenge", indices = [Index("code")])
data class ChallengeEntity(
    @PrimaryKey val id: String,
    val code: String,
    val title: String,
    val description: String,
    val type: String,
    val period: String,
    val targetValue: Double,
    val rewardCoins: Long,
    val isActive: Boolean,
    val startsAt: Long?,
    val endsAt: Long?,
    val updatedAt: Long,
)

@Entity(tableName = "challenge_progress", primaryKeys = ["challengeId", "periodKey"])
data class ChallengeProgressEntity(
    val challengeId: String,
    val periodKey: String,
    val currentValue: Double,
    val targetValue: Double,
    val completed: Boolean,
    val claimed: Boolean,
    val claimedAt: Long?,
    val syncedAt: Long?,
)

@Entity(tableName = "achievement")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val code: String,
    val title: String,
    val description: String,
    val icon: String,
    val category: String,
    val threshold: Double,
    val progress: Double,
    val unlockedAt: Long?,
    val updatedAt: Long,
)

@Entity(tableName = "reward")
data class RewardEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val category: String,
    val costCoins: Long,
    val emoji: String?,
    val stock: Int?,
    val isActive: Boolean,
    val fulfillmentNote: String?,
    val updatedAt: Long,
)

@Entity(tableName = "redemption", indices = [Index("status")])
data class RedemptionEntity(
    @PrimaryKey val id: String,
    val rewardId: String,
    val rewardTitle: String,
    val costCoins: Long,
    val status: String,
    val note: String?,
    val requestedAt: Long,
    val updatedAt: Long,
)

/** Cached weekly plan produced by the coach engine (local rules or server rules). */
@Entity(tableName = "coach_plan")
data class CoachPlanEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val weekStartDayKey: String,
    val goal: String,
    val summary: String,
    val engineVersion: String,
    val generatedBy: String,
    val payloadJson: String,
)
