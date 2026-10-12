package com.vtrack.app.domain.engine

import com.google.common.truth.Truth.assertThat
import com.vtrack.app.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the client-side V-Coin estimate against the *same* golden table that
 * `backend/test/coin-rules.spec.ts` asserts for the server copy. If either
 * implementation changes a rule on its own, one of the two suites goes red —
 * which is the whole point: the app must never promise coins the server will
 * not pay.
 */
class CoinRulesParityTest {

    // Mirrors STEP_GOLDEN in coin-rules.spec.ts.
    private val stepGolden = listOf(
        0 to 0L,
        999 to 0L,
        1_000 to 0L,
        1_001 to 0L,
        2_000 to 1L,
        3_000 to 2L,
        5_000 to 4L,
        8_000 to 7L,
        9_000 to 8L,
        10_000 to 8L,
        50_000 to 8L,
        100_000 to 8L,
    )

    // Mirrors WORKOUT_GOLDEN in coin-rules.spec.ts.
    private val workoutGolden = listOf(
        Triple(ActivityType.WALKING, 599L, 0L),
        Triple(ActivityType.WALKING, 600L, 1L),
        Triple(ActivityType.WALKING, 1_799L, 1L),
        Triple(ActivityType.WALKING, 1_800L, 2L),
        Triple(ActivityType.WALKING, 3_600L, 3L),
        Triple(ActivityType.WALKING, 5_400L, 4L),
        Triple(ActivityType.WALKING, 7_200L, 5L),
        Triple(ActivityType.WALKING, 9_000L, 6L),
        Triple(ActivityType.WALKING, 30_000L, 6L),
        Triple(ActivityType.RUNNING, 3_600L, 3L),
        Triple(ActivityType.CYCLING, 5_400L, 4L),
        Triple(ActivityType.HIKING, 7_200L, 5L),
        Triple(ActivityType.STRENGTH, 899L, 0L),
        Triple(ActivityType.STRENGTH, 900L, 1L),
        Triple(ActivityType.STRENGTH, 2_700L, 2L),
        Triple(ActivityType.BODYWEIGHT, 900L, 0L),
        Triple(ActivityType.BODYWEIGHT, 3_600L, 2L),
        Triple(ActivityType.YOGA, 1_199L, 0L),
        Triple(ActivityType.YOGA, 1_200L, 0L),
        Triple(ActivityType.YOGA, 1_800L, 1L),
        Triple(ActivityType.STRETCHING, 899L, 0L),
        Triple(ActivityType.STRETCHING, 900L, 0L),
        Triple(ActivityType.STRETCHING, 1_800L, 1L),
        Triple(ActivityType.MOBILITY, 900L, 0L),
        Triple(ActivityType.MOBILITY, 1_800L, 1L),
    )

    @Test
    fun stepsMatchTheServerGoldenTable() {
        for ((steps, expected) in stepGolden) {
            assertEquals("estimateStepCoins($steps)", expected, RewardRules.estimateStepCoins(steps))
        }
    }

    @Test
    fun creditsNothingBelowTheFloorAndCapsAboveTheCeiling() {
        assertThat(RewardRules.creditedSteps(999)).isEqualTo(0)
        assertThat(RewardRules.creditedSteps(1_000)).isEqualTo(1_000)
        assertThat(RewardRules.creditedSteps(20_000)).isEqualTo(20_000)
        assertThat(RewardRules.creditedSteps(60_000)).isEqualTo(20_000)
    }

    @Test
    fun neverExceedsTheDailyStepCoinCeiling() {
        assertThat(RewardRules.coinsForSteps(20_000)).isEqualTo(8L)
        assertThat(RewardRules.coinsForSteps(1_000_000)).isEqualTo(8L)
    }

    @Test
    fun reportsRemainingHeadroomAgainstTheDailyCap() {
        assertThat(RewardRules.remainingStepCoins(0)).isEqualTo(8L)
        assertThat(RewardRules.remainingStepCoins(5)).isEqualTo(3L)
        assertThat(RewardRules.remainingStepCoins(8)).isEqualTo(0L)
        assertThat(RewardRules.remainingStepCoins(99)).isEqualTo(0L)
    }

    @Test
    fun workoutsMatchTheServerGoldenTable() {
        for ((activity, seconds, expected) in workoutGolden) {
            assertEquals(
                "coinsForWorkout(${activity.name}, ${seconds}s)",
                expected,
                RewardRules.coinsForWorkout(activity, seconds),
            )
        }
    }

    @Test
    fun usesTheDocumentedPerActivityMinimumDurations() {
        assertThat(RewardRules.coinsForWorkout(ActivityType.RUNNING, 599L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.RUNNING, 600L)).isEqualTo(1L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.STRENGTH, 899L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.STRENGTH, 900L)).isEqualTo(1L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.BODYWEIGHT, 899L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.BODYWEIGHT, 900L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.STRETCHING, 899L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.MOBILITY, 899L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.YOGA, 1_199L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.YOGA, 1_200L)).isEqualTo(0L)
        assertThat(RewardRules.coinsForWorkout(ActivityType.YOGA, 1_800L)).isEqualTo(1L)
    }

    @Test
    fun streakBonusesMatchTheServerGoldenTable() {
        val golden = listOf(
            0 to 0L, 1 to 0L, 2 to 0L, 3 to 1L, 6 to 0L, 7 to 3L,
            13 to 0L, 14 to 6L, 30 to 15L, 60 to 25L, 100 to 40L, 365 to 150L,
        )
        for ((days, expected) in golden) {
            assertEquals("streakBonus($days)", expected, RewardRules.streakBonus(days))
        }
    }

    @Test
    fun knowsWhichDaysAreMilestones() {
        assertThat(RewardRules.isStreakMilestone(7)).isTrue()
        assertThat(RewardRules.isStreakMilestone(8)).isFalse()
    }

    @Test
    fun pointsAtTheNextMilestone() {
        assertThat(RewardRules.nextStreakMilestone(0)).isEqualTo(3 to 1L)
        assertThat(RewardRules.nextStreakMilestone(3)).isEqualTo(7 to 3L)
        assertThat(RewardRules.nextStreakMilestone(100)).isEqualTo(365 to 150L)
        assertThat(RewardRules.nextStreakMilestone(365)).isNull()
    }

    @Test
    fun plausibilityMatchesTheServer() {
        assertThat(RewardRules.isPlausible(8_000, 4 * 3_600)).isTrue()
        // 20,000 steps in one active minute.
        assertThat(RewardRules.isPlausible(20_000, 60)).isFalse()
        assertThat(RewardRules.isPlausible(0, 3_600)).isFalse()
        assertThat(RewardRules.isPlausible(60_001, 3_600)).isFalse()
        assertThat(RewardRules.isPlausible(15_000, 3_600)).isTrue()
    }

    /**
     * The numbers published on `GET /v1/config` and shown on the earning-rules
     * screen. Changing any of them here without changing the server would make
     * the app promise rewards the server does not pay.
     */
    @Test
    fun publishesTheSameRuleConstantsAsTheServer() {
        assertThat(RewardRules.ENGINE_VERSION).isEqualTo("vcoin-rules-1.0.0")
        assertThat(RewardRules.COINS_PER_STEP).isEqualTo(0.00095)
        assertThat(RewardRules.MIN_STEPS_FOR_REWARD).isEqualTo(1000)
        assertThat(RewardRules.MAX_STEP_COINS_PER_DAY).isEqualTo(8L)
        assertThat(RewardRules.MIN_CLAIM_GAP_SECONDS).isEqualTo(900L)
        assertThat(RewardRules.MAX_WORKOUT_REWARDS_PER_DAY).isEqualTo(3)
    }
}
