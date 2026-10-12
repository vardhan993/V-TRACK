package com.vtrack.app.data.repository

import com.vtrack.app.core.error.VTrackError
import com.vtrack.app.core.error.mapToVTrackError
import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.local.Mappers.toDomain
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.dao.RewardsDao
import com.vtrack.app.data.local.dao.SyncDao
import com.vtrack.app.data.local.entity.AchievementEntity
import com.vtrack.app.data.local.entity.ChallengeProgressEntity
import com.vtrack.app.data.local.entity.CoinTransactionEntity
import com.vtrack.app.data.local.entity.RedemptionEntity
import com.vtrack.app.data.local.entity.SyncQueueEntity
import com.vtrack.app.data.remote.RemoteMappers.toEntity
import com.vtrack.app.data.remote.VTrackApi
import com.vtrack.app.data.remote.dto.ClaimStepsRequest
import com.vtrack.app.data.remote.dto.ClaimWorkoutRequest
import com.vtrack.app.data.remote.dto.RedemptionRequest
import com.vtrack.app.domain.engine.RewardRules
import com.vtrack.app.domain.model.Achievement
import com.vtrack.app.domain.model.ChallengeProgress
import com.vtrack.app.domain.model.CoinBalance
import com.vtrack.app.domain.model.CoinStatus
import com.vtrack.app.domain.model.CoinTransaction
import com.vtrack.app.domain.model.CoinTransactionType
import com.vtrack.app.domain.model.Redemption
import com.vtrack.app.domain.model.Reward
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The V-Coin economy, client side.
 *
 * Hard rule enforced here: **the client never decides a balance.** It:
 *  1. shows an *estimate* derived from RewardRules for the steps recorded on this device,
 *  2. submits a claim (steps are sent as the cumulative day total, which the server turns
 *     into newly-credited steps and coins — so re-sending the same total earns nothing),
 *  3. mirrors whatever the server wrote back into the local ledger.
 *
 * V-Coins are virtual credits with no monetary value; there is no cash-out path anywhere
 * in this codebase, and the reward catalogue is fulfilled by the operator as in-app perks.
 */
@Singleton
class RewardsRepository @Inject constructor(
    private val rewardsDao: RewardsDao,
    private val activityDao: ActivityDao,
    private val profileDao: ProfileDao,
    private val syncDao: SyncDao,
    private val api: VTrackApi,
    private val networkMonitor: NetworkMonitor,
    private val sessionStore: SessionStore,
) {

    /* ------------------------------------------------------------- balances */

    /** Confirmed (server-written) + pending (submitted) + an honest local estimate. */
    fun observeBalance(): Flow<CoinBalance> {
        val todayKey = TimeUtils.todayKey()
        return combine(
            rewardsDao.observeConfirmedBalance(),
            rewardsDao.observeUnconfirmedBalance(),
            activityDao.observeDay(todayKey),
        ) { confirmed, pending, day ->
            val estimate = RewardRules.estimateStepCoins(day?.steps ?: 0)
            val alreadyConfirmedToday = 0L // server-reported earned-today arrives on refresh
            CoinBalance(
                confirmed = confirmed,
                pending = pending,
                estimatedToday = (estimate - alreadyConfirmedToday).coerceAtLeast(0L),
            )
        }
    }

    fun observeTransactions(limit: Int = 100): Flow<List<CoinTransaction>> =
        rewardsDao.observeTransactions(limit).map { list -> list.map { it.toDomain() } }

    /* ---------------------------------------------------------------- claims */

    /**
     * Submits today's cumulative step count. Safe to call repeatedly: the server credits
     * only the difference over what it already awarded for that day.
     */
    suspend fun claimStepsToday(): Result<ClaimOutcome> = runCatching {
        requireOnline()
        val dayKey = TimeUtils.todayKey()
        val day = activityDao.getDay(dayKey) ?: throw VTrackError.Rejected(
            "no_activity",
            "No steps recorded today yet — go for a walk and try again.",
        )
        if (day.steps < RewardRules.MIN_STEPS_FOR_REWARD) {
            throw VTrackError.Rejected(
                "below_minimum",
                "Rewards start at ${RewardRules.MIN_STEPS_FOR_REWARD} steps in a day.",
            )
        }
        if (!RewardRules.isPlausible(day.steps, day.activeMinutes * 60)) {
            throw VTrackError.Rejected("implausible", "This activity pattern could not be verified.")
        }

        val userId = sessionStore.userId() ?: throw VTrackError.Unauthorized()
        // The claim id only has to be unique per attempt; the server keys crediting off
        // (user, day) rather than this id, so a collision or replay simply earns nothing.
        val clientEventId = "steps:$userId:$dayKey:${UUID.randomUUID()}"

        val response = api.claimSteps(
            ClaimStepsRequest(
                clientEventId = clientEventId,
                dayKey = dayKey,
                steps = day.steps,
                activeSeconds = day.activeMinutes * 60,
                source = ActivityRepository.SOURCE_SENSOR,
                zoneOffsetMinutes = TimeUtils.zone().rules.getOffset(Instant.now()).totalSeconds / 60,
            ),
        )

        rewardsDao.upsertTransaction(
            CoinTransactionEntity(
                id = response.transactionId,
                clientEventId = response.clientEventId,
                type = CoinTransactionType.STEP_REWARD.name,
                amount = response.amount,
                reason = response.reason,
                activityRef = dayKey,
                status = CoinStatus.CONFIRMED.name,
                rejectionReason = null,
                balanceAfter = response.balance,
                createdAt = System.currentTimeMillis(),
                serverId = response.transactionId,
            ),
        )
        activityDao.updateVerifiedSteps(dayKey, day.steps, System.currentTimeMillis())

        ClaimOutcome(
            awardedCoins = response.amount,
            balanceAfter = response.balance,
            message = when {
                response.duplicate -> "Already credited — no new steps since the last claim."
                response.amount == 0L -> "No new reward-eligible steps since your last claim."
                else -> "+${response.amount} V-Coins verified."
            },
        )
    }.mapToVTrackError()

    /** Claims the reward for a finished workout (server re-checks duration and caps). */
    suspend fun claimWorkout(workoutClientId: String): Result<ClaimOutcome> = runCatching {
        requireOnline()
        val response = api.claimWorkout(ClaimWorkoutRequest(UUID.randomUUID().toString(), workoutClientId))
        rewardsDao.upsertTransaction(
            CoinTransactionEntity(
                id = response.transactionId,
                clientEventId = response.clientEventId,
                type = CoinTransactionType.WORKOUT_REWARD.name,
                amount = response.amount,
                reason = response.reason,
                activityRef = workoutClientId,
                status = CoinStatus.CONFIRMED.name,
                rejectionReason = null,
                balanceAfter = response.balance,
                createdAt = System.currentTimeMillis(),
                serverId = response.transactionId,
            ),
        )
        ClaimOutcome(response.amount, response.balance, response.reason)
    }.mapToVTrackError()

    /** Pending claims left over from an offline session, retried by the sync worker. */
    suspend fun submitPendingClaims(): Int {
        val pending = rewardsDao.unconfirmedTransactions(20)
        var submitted = 0
        pending.forEach { entity ->
            when (entity.type) {
                CoinTransactionType.STEP_REWARD.name -> {
                    claimStepsToday().onSuccess { submitted++ }
                    // The server recomputes from the day, so a single attempt is enough.
                }
                CoinTransactionType.WORKOUT_REWARD.name -> {
                    val workoutId = entity.activityRef ?: return@forEach
                    claimWorkout(workoutId).onSuccess { submitted++ }
                }
                else -> Unit
            }
        }
        return submitted
    }

    /* -------------------------------------------------------------- catalog */

    fun observeChallenges(periodKey: String = TimeUtils.todayKey()): Flow<List<ChallengeProgress>> =
        combine(
            rewardsDao.observeChallenges(),
            rewardsDao.observeProgressForPeriod(periodKey),
        ) { challenges, progress ->
            val byId = progress.associateBy { it.challengeId }
            challenges.map { it.toDomain(byId[it.id]) }
        }

    fun observeAchievements(): Flow<List<Achievement>> =
        rewardsDao.observeAchievements().map { list -> list.map { it.toDomain() } }

    fun observeRewards(): Flow<List<Reward>> =
        rewardsDao.observeRewards().map { list -> list.map { it.toDomain() } }

    fun observeRedemptions(): Flow<List<Redemption>> =
        rewardsDao.observeRedemptions().map { list -> list.map { it.toDomain() } }

    suspend fun claimChallenge(challengeId: String): Result<ClaimOutcome> = runCatching {
        requireOnline()
        val response = api.claimChallenge(challengeId)
        rewardsDao.upsertTransaction(
            CoinTransactionEntity(
                id = response.transactionId,
                clientEventId = response.clientEventId,
                type = CoinTransactionType.CHALLENGE_BONUS.name,
                amount = response.amount,
                reason = response.reason,
                activityRef = challengeId,
                status = CoinStatus.CONFIRMED.name,
                rejectionReason = null,
                balanceAfter = response.balance,
                createdAt = System.currentTimeMillis(),
                serverId = response.transactionId,
            ),
        )
        ClaimOutcome(response.amount, response.balance, response.reason)
    }.mapToVTrackError()

    /**
     * Requests a redemption. The server debits the balance atomically inside a D1
     * transaction and refuses when the balance is insufficient, so the app can never
     * spend coins it does not have.
     */
    suspend fun redeem(rewardId: String, note: String? = null): Result<Redemption> = runCatching {
        requireOnline()
        val dto = api.redeem(rewardId, RedemptionRequest(note))
        val entity = dto.toEntity()
        rewardsDao.upsertRedemption(entity)
        entity.toDomain()
    }.mapToVTrackError()

    /* ----------------------------------------------------------- refresh */

    suspend fun refreshFromServer(): Result<Unit> = runCatching<Unit> {
        if (!networkMonitor.isCurrentlyOnline() || !sessionStore.isSignedIn()) {
            return@runCatching
        }

        runCatching { api.balance() }.getOrNull()?.let { balance ->
            // Rebuild the confirmed ledger view if the server reports a different total.
            val local = rewardsDao.confirmedBalance()
            if (local != balance.confirmed) {
                syncDao.enqueue(
                    SyncQueueEntity(
                        entityType = "LEDGER",
                        entityId = "balance",
                        operation = "PULL",
                        createdAt = System.currentTimeMillis(),
                        nextAttemptAt = 0L,
                    ),
                )
            }
        }

        runCatching { api.transactions(limit = 100) }.getOrNull()?.let { page ->
            page.items.forEach { dto -> rewardsDao.upsertTransaction(dto.toEntity()) }
        }
        runCatching { api.challenges() }.getOrNull()?.let { list ->
            rewardsDao.upsertChallenges(list.items.map { it.toEntity() })
            list.items.forEach { dto ->
                rewardsDao.upsertChallengeProgress(
                    ChallengeProgressEntity(
                        challengeId = dto.id,
                        periodKey = dto.periodKey.ifBlank { TimeUtils.todayKey() },
                        currentValue = dto.currentValue,
                        targetValue = dto.targetValue,
                        completed = dto.completed,
                        claimed = dto.claimed,
                        claimedAt = null,
                        syncedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        runCatching { api.achievements() }.getOrNull()?.let { list ->
            rewardsDao.upsertAchievements(list.map { it.toEntity() })
        }
        runCatching { api.rewards() }.getOrNull()?.let { list ->
            rewardsDao.upsertRewards(list.map { it.toEntity() })
        }
        runCatching { api.redemptions() }.getOrNull()?.let { list ->
            list.forEach { rewardsDao.upsertRedemption(it.toEntity()) }
        }
    }.mapToVTrackError()

    /* -------------------------------------------------------- achievements */

    /**
     * Local achievement evaluation. These mirror the server's definitions; the server
     * remains the source of truth for any bonus it pays out.
     */
    suspend fun evaluateLocalAchievements(
        totalSteps: Int,
        totalWorkouts: Int,
        streakDays: Int,
        bestStreak: Int,
        longestDistanceMeters: Double,
        totalSets: Int,
        totalActiveMinutes: Int,
    ) {
        val now = System.currentTimeMillis()
        val definitions = listOf(
            Triple("first_10000_steps", 10_000.0, totalSteps.toDouble()),
            Triple("steps_100k", 100_000.0, totalSteps.toDouble()),
            Triple("steps_500k", 500_000.0, totalSteps.toDouble()),
            Triple("first_workout", 1.0, totalWorkouts.toDouble()),
            Triple("workouts_25", 25.0, totalWorkouts.toDouble()),
            Triple("streak_7", 7.0, streakDays.toDouble()),
            Triple("streak_30", 30.0, bestStreak.toDouble()),
            Triple("distance_marathon", 42_195.0, longestDistanceMeters),
            Triple("sets_500", 500.0, totalSets.toDouble()),
            Triple("active_minutes_1000", 1_000.0, totalActiveMinutes.toDouble()),
        )
        // Catalog rows come from the server when available; only progress and unlock
        // timestamps are updated locally, and only for definitions that exist.
        definitions.forEach { (code, _, progress) ->
            rewardsDao.getAchievement(code)?.let {
                rewardsDao.updateAchievement(code, progress, now)
            }
        }
    }

    /** Installs the bundled achievement catalog, preserving any progress already recorded. */
    suspend fun seedAchievementCatalog() {
        val now = System.currentTimeMillis()
        rewardsDao.upsertAchievements(
            listOf(
                AchievementDefinition("first_10000_steps", "10,000 steps", "Get 10,000 steps in a day", "👟", "steps", 10_000.0),
                AchievementDefinition("steps_100k", "100k club", "Walk 100,000 lifetime steps", "🚶", "steps", 100_000.0),
                AchievementDefinition("steps_500k", "Half a million", "Walk 500,000 lifetime steps", "🏅", "steps", 500_000.0),
                AchievementDefinition("first_workout", "First session", "Complete your first workout", "🎯", "workouts", 1.0),
                AchievementDefinition("workouts_25", "25 sessions", "Complete 25 workouts", "💪", "workouts", 25.0),
                AchievementDefinition("streak_7", "Week streak", "Stay active 7 days in a row", "🔥", "streak", 7.0),
                AchievementDefinition("streak_30", "Month streak", "Stay active 30 days in a row", "🌟", "streak", 30.0),
                AchievementDefinition("distance_marathon", "Marathon distance", "Cover 42.2 km in a single activity", "🏃", "distance", 42_195.0),
                AchievementDefinition("sets_500", "500 sets", "Log 500 strength sets", "🏋️", "strength", 500.0),
                AchievementDefinition("active_minutes_1000", "1,000 active minutes", "Accumulate 1,000 active minutes", "⏱️", "minutes", 1_000.0),
            ).map { def ->
                AchievementEntity(
                    id = "ach.${def.code}",
                    code = def.code,
                    title = def.title,
                    description = def.description,
                    icon = def.icon,
                    category = def.category,
                    threshold = def.threshold,
                    progress = rewardsDao.getAchievement(def.code)?.progress ?: 0.0,
                    unlockedAt = rewardsDao.getAchievement(def.code)?.unlockedAt,
                    updatedAt = now,
                )
            },
        )
    }

    private data class AchievementDefinition(
        val code: String,
        val title: String,
        val description: String,
        val icon: String,
        val category: String,
        val threshold: Double,
    )

    private fun requireOnline() {
        if (!networkMonitor.isCurrentlyOnline()) {
            throw VTrackError.Offline()
        }
    }

    data class ClaimOutcome(val awardedCoins: Long, val balanceAfter: Long, val message: String)
}
