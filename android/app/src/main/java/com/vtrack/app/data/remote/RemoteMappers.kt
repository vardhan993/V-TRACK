package com.vtrack.app.data.remote

import com.vtrack.app.data.local.entity.AchievementEntity
import com.vtrack.app.data.local.entity.ChallengeEntity
import com.vtrack.app.data.local.entity.CoinTransactionEntity
import com.vtrack.app.data.local.entity.RedemptionEntity
import com.vtrack.app.data.local.entity.RewardEntity
import com.vtrack.app.data.remote.dto.AchievementDto
import com.vtrack.app.data.remote.dto.ChallengeDto
import com.vtrack.app.data.remote.dto.CoinTransactionDto
import com.vtrack.app.data.remote.dto.RedemptionDto
import com.vtrack.app.data.remote.dto.RewardDto
import com.vtrack.app.data.remote.dto.UserDto
import com.vtrack.app.domain.model.CoinStatus
import java.time.Instant

/**
 * Server → local mapping. Server state always wins for coin data and challenge catalog
 * because the server is authoritative there; local device activity stays local until it
 * has been uploaded.
 */
object RemoteMappers {

    fun UserDto.toProfileFields(): ProfileFields = ProfileFields(
        id = id,
        email = email,
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarUrl = avatarUrl,
        birthYear = birthYear,
        sex = sex,
        heightCm = heightCm,
        weightKg = weightKg,
        fitnessLevel = fitnessLevel,
        primaryGoal = primaryGoal,
        preferredActivities = preferredActivities.joinToString(","),
        weeklyWorkoutTarget = weeklyWorkoutTarget,
        unitSystem = unitSystem,
        stepGoal = stepGoal,
        onboardingComplete = onboardingComplete,
        healthConnectEnabled = healthConnectEnabled,
        shareRoutes = shareRoutes,
        shareActivityWithCoach = shareActivityWithCoach,
        updatedAt = if (updatedAt > 0) updatedAt else Instant.now().toEpochMilli(),
    )

    data class ProfileFields(
        val id: String,
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
        val preferredActivities: String,
        val weeklyWorkoutTarget: Int,
        val unitSystem: String,
        val stepGoal: Int,
        val onboardingComplete: Boolean,
        val healthConnectEnabled: Boolean,
        val shareRoutes: Boolean,
        val shareActivityWithCoach: Boolean,
        val updatedAt: Long,
    )

    fun CoinTransactionDto.toEntity(): CoinTransactionEntity = CoinTransactionEntity(
        id = id,
        clientEventId = clientEventId,
        type = type,
        amount = amount,
        reason = reason,
        activityRef = activityRef,
        status = runCatching { CoinStatus.valueOf(status) }.getOrDefault(CoinStatus.CONFIRMED).name,
        rejectionReason = null,
        balanceAfter = balanceAfter,
        createdAt = createdAt,
        serverId = id,
    )

    fun ChallengeDto.toEntity(): ChallengeEntity = ChallengeEntity(
        id = id,
        code = code,
        title = title,
        description = description,
        type = type,
        period = period,
        targetValue = targetValue,
        rewardCoins = rewardCoins,
        isActive = isActive,
        startsAt = null,
        endsAt = null,
        updatedAt = Instant.now().toEpochMilli(),
    )

    fun AchievementDto.toEntity(): AchievementEntity = AchievementEntity(
        id = id,
        code = code,
        title = title,
        description = description,
        icon = icon,
        category = category,
        threshold = threshold,
        progress = progress,
        unlockedAt = unlockedAt,
        updatedAt = Instant.now().toEpochMilli(),
    )

    fun RewardDto.toEntity(): RewardEntity = RewardEntity(
        id = id,
        title = title,
        description = description,
        category = category,
        costCoins = costCoins,
        emoji = emoji,
        stock = stock,
        isActive = isActive,
        fulfillmentNote = fulfillmentNote,
        updatedAt = Instant.now().toEpochMilli(),
    )

    fun RedemptionDto.toEntity(): RedemptionEntity = RedemptionEntity(
        id = id,
        rewardId = rewardId,
        rewardTitle = rewardTitle,
        costCoins = costCoins,
        status = status,
        note = note,
        requestedAt = requestedAt,
        updatedAt = Instant.now().toEpochMilli(),
    )
}
