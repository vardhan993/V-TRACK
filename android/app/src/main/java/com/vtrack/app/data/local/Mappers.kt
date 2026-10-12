package com.vtrack.app.data.local

import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.local.entity.ActivityDayEntity
import com.vtrack.app.data.local.entity.AchievementEntity
import com.vtrack.app.data.local.entity.ChallengeEntity
import com.vtrack.app.data.local.entity.ChallengeProgressEntity
import com.vtrack.app.data.local.entity.CoinTransactionEntity
import com.vtrack.app.data.local.entity.ExerciseEntity
import com.vtrack.app.data.local.entity.RedemptionEntity
import com.vtrack.app.data.local.entity.RewardEntity
import com.vtrack.app.data.local.entity.RoutineEntity
import com.vtrack.app.data.local.entity.RoutineItemEntity
import com.vtrack.app.data.local.entity.StepSampleEntity
import com.vtrack.app.data.local.entity.StrengthSetEntity
import com.vtrack.app.data.local.entity.UserProfileEntity
import com.vtrack.app.data.local.entity.WorkoutPointEntity
import com.vtrack.app.data.local.entity.WorkoutSessionEntity
import com.vtrack.app.domain.model.Achievement
import com.vtrack.app.domain.model.ActivityCategory
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.Challenge
import com.vtrack.app.domain.model.ChallengePeriod
import com.vtrack.app.domain.model.ChallengeProgress
import com.vtrack.app.domain.model.ChallengeType
import com.vtrack.app.domain.model.CoinStatus
import com.vtrack.app.domain.model.CoinTransaction
import com.vtrack.app.domain.model.CoinTransactionType
import com.vtrack.app.domain.model.DailyActivity
import com.vtrack.app.domain.model.Exercise
import com.vtrack.app.domain.model.FitnessLevel
import com.vtrack.app.domain.model.GpsPoint
import com.vtrack.app.domain.model.PrimaryGoal
import com.vtrack.app.domain.model.Redemption
import com.vtrack.app.domain.model.RedemptionStatus
import com.vtrack.app.domain.model.Reward
import com.vtrack.app.domain.model.Routine
import com.vtrack.app.domain.model.RoutineItem
import com.vtrack.app.domain.model.StrengthSet
import com.vtrack.app.domain.model.SyncState
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.domain.model.WorkoutSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Entity → domain mapping. Kept in one place so both directions stay obvious. */
object Mappers {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())

    /* ------------------------------------------------------------- profile */

    fun UserProfileEntity.toDomain(): UserProfile = UserProfile(
        id = id,
        email = email,
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarUrl = avatarUrl,
        birthYear = birthYear,
        sex = sex,
        heightCm = heightCm,
        weightKg = weightKg,
        fitnessLevel = FitnessLevel.fromStorage(fitnessLevel),
        primaryGoal = PrimaryGoal.fromStorage(primaryGoal),
        preferredActivities = preferredActivities
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.map { ActivityType.fromStorage(it) }
            ?: emptyList(),
        weeklyWorkoutTarget = weeklyWorkoutTarget,
        unitSystem = if (unitSystem.equals("IMPERIAL", true)) UnitSystem.IMPERIAL else UnitSystem.METRIC,
        stepGoal = stepGoal,
        onboardingComplete = onboardingComplete,
        healthConnectEnabled = healthConnectEnabled,
        shareRoutes = shareRoutes,
        shareActivityWithCoach = shareActivityWithCoach,
    )

    fun UserProfile.toEntity(existing: UserProfileEntity? = null): UserProfileEntity = UserProfileEntity(
        id = id,
        email = email,
        displayName = displayName,
        avatarEmoji = avatarEmoji,
        avatarUrl = avatarUrl,
        birthYear = birthYear,
        sex = sex,
        heightCm = heightCm,
        weightKg = weightKg,
        fitnessLevel = fitnessLevel.name,
        primaryGoal = primaryGoal.name,
        preferredActivities = preferredActivities.joinToString(",") { it.name },
        weeklyWorkoutTarget = weeklyWorkoutTarget,
        unitSystem = unitSystem.name,
        stepGoal = stepGoal,
        onboardingComplete = onboardingComplete,
        healthConnectEnabled = healthConnectEnabled,
        shareRoutes = shareRoutes,
        shareActivityWithCoach = shareActivityWithCoach,
        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        syncedAt = existing?.syncedAt,
    )

    /* ------------------------------------------------------------ activity */

    fun ActivityDayEntity.toDomain(): DailyActivity = DailyActivity(
        date = TimeUtils.parseDayKey(dayKey),
        steps = steps,
        verifiedSteps = verifiedSteps,
        distanceMeters = distanceMeters,
        calories = calories,
        activeMinutes = activeMinutes,
        goalSteps = goalSteps,
        firstSampleAt = firstSampleAt,
        lastSampleAt = lastSampleAt,
    )

    fun StepSampleEntity.toDomain(): SampleRow = SampleRow(
        dayKey = dayKey,
        hour = hourOfDay,
        source = source,
        steps = steps,
        distanceMeters = distanceMeters,
        calories = calories,
        activeSeconds = activeSeconds,
    )

    data class SampleRow(
        val dayKey: String,
        val hour: Int,
        val source: String,
        val steps: Int,
        val distanceMeters: Double,
        val calories: Double,
        val activeSeconds: Int,
    )

    /* ------------------------------------------------------------ workouts */

    fun WorkoutSessionEntity.toDomain(): WorkoutSession = WorkoutSession(
        id = id,
        activityType = ActivityType.fromStorage(activityType),
        title = title,
        startedAt = startedAt,
        endedAt = endedAt,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        calories = calories,
        avgPaceSecPerKm = avgPaceSecPerKm,
        avgSpeedKmh = avgSpeedKmh,
        steps = steps,
        elevationGainMeters = elevationGainMeters,
        indoor = indoor,
        manual = manual,
        autoTracked = autoTracked,
        notes = notes,
        perceivedEffort = perceivedEffort,
        syncState = runCatching { SyncState.valueOf(syncState) }.getOrDefault(SyncState.PENDING),
    )

    fun WorkoutSession.toEntity(existing: WorkoutSessionEntity? = null): WorkoutSessionEntity =
        WorkoutSessionEntity(
            id = id,
            activityType = activityType.name,
            title = title,
            startedAt = startedAt,
            endedAt = endedAt,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            calories = calories,
            avgPaceSecPerKm = avgPaceSecPerKm,
            avgSpeedKmh = avgSpeedKmh,
            steps = steps,
            elevationGainMeters = elevationGainMeters,
            indoor = indoor,
            manual = manual,
            autoTracked = autoTracked,
            notes = notes,
            perceivedEffort = perceivedEffort,
            syncState = syncState.name,
            syncedAt = existing?.syncedAt,
            serverId = existing?.serverId,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )

    fun WorkoutPointEntity.toDomain(): GpsPoint = GpsPoint(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracyMeters = accuracyMeters,
        speedMps = speedMps,
        recordedAt = recordedAt,
    )

    fun StrengthSet.toEntity(): StrengthSetEntity = StrengthSetEntity(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        setIndex = setIndex,
        reps = reps,
        weightKg = weightKg,
        durationSeconds = durationSeconds,
        distanceMeters = null,
        restSeconds = restSeconds,
        rpe = rpe,
        isWarmup = isWarmup,
        completedAt = completedAt,
        syncState = SyncState.PENDING.name,
    )

    fun StrengthSetEntity.toDomain(): StrengthSet = StrengthSet(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        setIndex = setIndex,
        reps = reps,
        weightKg = weightKg,
        durationSeconds = durationSeconds,
        restSeconds = restSeconds,
        rpe = rpe,
        isWarmup = isWarmup,
        completedAt = completedAt,
    )

    fun ExerciseEntity.toDomain(): Exercise = Exercise(
        id = id,
        name = name,
        category = runCatching { ActivityCategory.valueOf(category) }.getOrDefault(ActivityCategory.OTHER),
        primaryMuscle = primaryMuscle,
        equipment = equipment,
        difficulty = FitnessLevel.fromStorage(difficulty),
        metValue = metValue,
        instructions = decodeList(instructionsJson),
        cues = decodeList(cuesJson),
        commonMistakes = decodeList(commonMistakesJson),
        defaultSets = defaultSets,
        defaultReps = defaultReps,
        defaultSeconds = defaultSeconds,
        isCompound = isCompound,
        isCustom = isCustom,
    )

    fun Exercise.toEntity(): ExerciseEntity = ExerciseEntity(
        id = id,
        name = name,
        category = category.name,
        primaryMuscle = primaryMuscle,
        equipment = equipment,
        difficulty = difficulty.name,
        metValue = metValue,
        instructionsJson = encodeList(instructions),
        cuesJson = encodeList(cues),
        commonMistakesJson = encodeList(commonMistakes),
        defaultSets = defaultSets,
        defaultReps = defaultReps,
        defaultSeconds = defaultSeconds,
        isCompound = isCompound,
        isCustom = isCustom,
        createdAt = System.currentTimeMillis(),
    )

    fun RoutineEntity.toDomain(items: List<RoutineItemEntity>): Routine = Routine(
        id = id,
        name = name,
        description = description,
        activityType = ActivityType.fromStorage(activityType),
        scheduledWeekdays = scheduledWeekdays
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList(),
        items = items.map { it.toDomain() },
    )

    fun RoutineItemEntity.toDomain(): RoutineItem = RoutineItem(
        position = position,
        exerciseId = exerciseId,
        label = label,
        sets = sets,
        reps = reps,
        durationSeconds = durationSeconds,
        restSeconds = restSeconds,
    )

    /* ------------------------------------------------------------- rewards */

    fun CoinTransactionEntity.toDomain(): CoinTransaction = CoinTransaction(
        id = id,
        clientEventId = clientEventId,
        type = CoinTransactionType.fromStorage(type),
        amount = amount,
        reason = reason,
        activityRef = activityRef,
        status = runCatching { CoinStatus.valueOf(status) }.getOrDefault(CoinStatus.PENDING),
        createdAt = createdAt,
        rejectionReason = rejectionReason,
    )

    fun ChallengeEntity.toDomain(): Challenge = Challenge(
        id = id,
        code = code,
        title = title,
        description = description,
        type = ChallengeType.fromStorage(type),
        period = ChallengePeriod.fromStorage(period),
        targetValue = targetValue,
        rewardCoins = rewardCoins,
        isActive = isActive,
    )

    fun ChallengeEntity.toDomain(progress: ChallengeProgressEntity?): ChallengeProgress =
        ChallengeProgress(
            challenge = toDomain(),
            currentValue = progress?.currentValue ?: 0.0,
            completed = progress?.completed ?: false,
            claimed = progress?.claimed ?: false,
            periodKey = progress?.periodKey.orEmpty(),
        )

    fun AchievementEntity.toDomain(): Achievement = Achievement(
        id = id,
        code = code,
        title = title,
        description = description,
        icon = icon,
        category = category,
        threshold = threshold,
        progress = progress,
        unlockedAt = unlockedAt,
    )

    fun RewardEntity.toDomain(): Reward = Reward(
        id = id,
        title = title,
        description = description,
        category = category,
        costCoins = costCoins,
        emoji = emoji,
        stock = stock,
        fulfillmentNote = fulfillmentNote,
    )

    fun RedemptionEntity.toDomain(): Redemption = Redemption(
        id = id,
        rewardId = rewardId,
        rewardTitle = rewardTitle,
        costCoins = costCoins,
        status = RedemptionStatus.fromStorage(status),
        note = note,
        requestedAt = requestedAt,
    )

    /* ------------------------------------------------------------ helpers */

    private fun encodeList(values: List<String>): String = json.encodeToString(stringList, values)

    private fun decodeList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(stringList, value) }.getOrDefault(emptyList())
}
