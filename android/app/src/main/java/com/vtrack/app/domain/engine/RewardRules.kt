package com.vtrack.app.domain.engine

import com.vtrack.app.domain.model.ActivityType
import kotlin.math.floor

/**
 * V-Coin earning rules — the client copy.
 *
 * IMPORTANT: this file is a 1:1 mirror of `backend/src/services/coinRules.ts`. The client
 * copy exists only to show an honest *estimate* before the server answers; it is never
 * authoritative. The server re-evaluates every claim with the same rule set plus caps,
 * plausibility checks and idempotency, and the balance the app displays comes from the
 * server's ledger. `CoinRulesParityTest` (JVM) and `coin-rules.spec.ts` (Workers) pin the
 * same golden values on both sides so the two cannot drift silently.
 *
 * V-Coins are virtual credits inside V-Track. They have no monetary value, are not
 * withdrawable, and rewards are subject to the daily caps below.
 */
object RewardRules {

    const val ENGINE_VERSION = "vcoin-rules-1.0.0"

    /* ---------------- steps ---------------- */

    /** Coins earned per single step, before caps. ≈ 0.95 coins per 1,000 steps. */
    const val COINS_PER_STEP = 0.00095

    /** Below this, a day earns nothing — prevents micro-claims with no real activity. */
    const val MIN_STEPS_FOR_REWARD = 1000

    /** Steps beyond this per day do not earn (anti-farming ceiling). */
    const val MAX_CREDITED_STEPS_PER_DAY = 20_000

    /** Hard ceiling on step coins per local day. */
    const val MAX_STEP_COINS_PER_DAY = 8L

    /** Claims are only allowed once the previous claim is [MIN_CLAIM_GAP_SECONDS] old. */
    const val MIN_CLAIM_GAP_SECONDS = 900L

    /** Plausibility: nobody walks faster than this sustained average. */
    const val MAX_PLAUSIBLE_STEPS_PER_MINUTE = 220
    const val MAX_PLAUSIBLE_STEPS_PER_DAY = 60_000

    /* ---------------- workouts ---------------- */

    /** Minimum real duration before a workout can earn. */
    const val MIN_WORKOUT_SECONDS_FOR_REWARD = 600L

    /** Base coins for any qualifying workout. */
    const val WORKOUT_BASE_COINS = 1L

    /** Extra coin per full 30 minutes of activity. */
    const val WORKOUT_COINS_PER_30_MIN = 1L

    /** Longer sessions earn more, with a ceiling so nothing can be mass-produced. */
    const val MAX_WORKOUT_COINS = 6L

    /** At most this many rewarded workout sessions per local day. */
    const val MAX_WORKOUT_REWARDS_PER_DAY = 3

    /** Multipliers by activity type — strength/interval work is capped a little lower. */
    private val workoutMultiplier: Map<ActivityType, Double> = mapOf(
        ActivityType.WALKING to 1.0,
        ActivityType.RUNNING to 1.2,
        ActivityType.CYCLING to 1.1,
        ActivityType.HIKING to 1.1,
        ActivityType.TREADMILL to 1.1,
        ActivityType.ELLIPTICAL to 1.0,
        ActivityType.ROWING to 1.1,
        ActivityType.SWIMMING to 1.1,
        ActivityType.STAIR_CLIMBING to 1.0,
        ActivityType.STRENGTH to 1.0,
        ActivityType.BODYWEIGHT to 0.9,
        ActivityType.HIIT to 1.0,
        ActivityType.YOGA to 0.8,
        ActivityType.STRETCHING to 0.7,
        ActivityType.MOBILITY to 0.7,
        ActivityType.DANCE to 1.0,
        ActivityType.SPORT to 1.1,
        ActivityType.OTHER to 0.8,
    )

    /* ---------------- streaks ---------------- */

    /** Milestone streak days → bonus coins, awarded once per milestone. */
    private val streakMilestones: Map<Int, Long> = mapOf(
        3 to 1L,
        7 to 3L,
        14 to 6L,
        30 to 15L,
        60 to 25L,
        100 to 40L,
        365 to 150L,
    )

    /* ---------------- pure calculations ---------------- */

    /** Steps that actually earn: floor-guarded, capped, and zero below the minimum. */
    fun creditedSteps(rawSteps: Int): Int = when {
        rawSteps < MIN_STEPS_FOR_REWARD -> 0
        rawSteps > MAX_CREDITED_STEPS_PER_DAY -> MAX_CREDITED_STEPS_PER_DAY
        else -> rawSteps
    }

    fun coinsForSteps(creditedSteps: Int): Long =
        floor(creditedSteps * COINS_PER_STEP).toLong().coerceAtMost(MAX_STEP_COINS_PER_DAY)

    /** Estimated step coins for a day given the raw step count. */
    fun estimateStepCoins(rawSteps: Int): Long = coinsForSteps(creditedSteps(rawSteps))

    fun coinsForWorkout(activity: ActivityType, durationSeconds: Long): Long {
        if (durationSeconds < minWorkoutSeconds(activity)) return 0L
        val multiplier = workoutMultiplier[activity] ?: 1.0
        val halfHours = durationSeconds / 1800.0
        val raw = (WORKOUT_BASE_COINS + floor(halfHours).toLong() * WORKOUT_COINS_PER_30_MIN) * multiplier
        return floor(raw).toLong().coerceIn(0L, MAX_WORKOUT_COINS)
    }

    /** Yoga and stretching need slightly more time to count as a session. */
    private fun minWorkoutSeconds(activity: ActivityType): Long = when (activity) {
        ActivityType.STRETCHING, ActivityType.MOBILITY -> 900L
        ActivityType.YOGA -> 1_200L
        ActivityType.STRENGTH, ActivityType.BODYWEIGHT -> 900L
        else -> MIN_WORKOUT_SECONDS_FOR_REWARD
    }

    /** Bonus for reaching (not exceeding) a streak milestone — 0 when there is no milestone. */
    fun streakBonus(streakDays: Int): Long = streakMilestones[streakDays] ?: 0L

    fun isStreakMilestone(streakDays: Int): Boolean = streakMilestones.containsKey(streakDays)

    /** Next milestone above [streakDays], for "3 days to go" style copy. */
    fun nextStreakMilestone(streakDays: Int): Pair<Int, Long>? =
        streakMilestones.entries
            .filter { it.key > streakDays }
            .minByOrNull { it.key }
            ?.let { it.key to it.value }

    /** Plausibility guard used before a claim is submitted. */
    fun isPlausible(daySteps: Int, activeSeconds: Int): Boolean {
        if (daySteps > MAX_PLAUSIBLE_STEPS_PER_DAY) return false
        if (daySteps <= 0) return false
        val minutes = (activeSeconds / 60).coerceAtLeast(1)
        return daySteps / minutes <= MAX_PLAUSIBLE_STEPS_PER_MINUTE || minutes >= 60
    }

    /** Remaining step-coin headroom for the day, given coins already awarded. */
    fun remainingStepCoins(coinsAlreadyAwarded: Long): Long =
        (MAX_STEP_COINS_PER_DAY - coinsAlreadyAwarded).coerceAtLeast(0L)
}
