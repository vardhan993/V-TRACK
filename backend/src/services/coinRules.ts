import type { Env } from "../env";
import { floatVar, intVar } from "../env";

/**
 * V-Coin earning rules — the server copy.
 *
 * This is a 1:1 mirror of `android/.../domain/engine/RewardRules.kt`. The client
 * keeps the same numbers so it can show an honest *estimate* while offline; the
 * server re-evaluates every claim and owns the real balance. The golden values
 * are pinned on both sides by `test/coin-rules.spec.ts` and the JVM
 * `CoinRulesParityTest`, so the two cannot drift silently.
 *
 * Every limit is overridable through a wrangler var (the "configurable earning
 * rates" requirement) and published by `GET /v1/config`. Changing a var changes
 * what the server awards — the client estimate catches up next time it reads
 * config, and never becomes authoritative.
 *
 * V-Coins are virtual credits inside V-Track. They have no monetary value, are
 * not withdrawable, and there is no cash-out path anywhere in this codebase.
 */

export interface CoinRules {
  readonly engineVersion: string;
  readonly coinsPerStep: number;
  readonly minStepsForReward: number;
  readonly maxCreditedStepsPerDay: number;
  readonly maxStepCoinsPerDay: number;
  readonly minClaimGapSeconds: number;
  readonly maxPlausibleStepsPerMinute: number;
  readonly maxPlausibleStepsPerDay: number;
  readonly minWorkoutSecondsForReward: number;
  readonly workoutBaseCoins: number;
  readonly workoutCoinsPer30Min: number;
  readonly maxWorkoutCoins: number;
  readonly maxWorkoutRewardsPerDay: number;
}

/** Compile-time defaults; identical to RewardRules.kt on the Android side. */
export const DEFAULT_RULES: CoinRules = Object.freeze({
  engineVersion: "vcoin-rules-1.0.0",
  coinsPerStep: 0.00095,
  minStepsForReward: 1000,
  maxCreditedStepsPerDay: 20_000,
  maxStepCoinsPerDay: 8,
  minClaimGapSeconds: 900,
  maxPlausibleStepsPerMinute: 220,
  maxPlausibleStepsPerDay: 60_000,
  minWorkoutSecondsForReward: 600,
  workoutBaseCoins: 1,
  workoutCoinsPer30Min: 1,
  maxWorkoutCoins: 6,
  maxWorkoutRewardsPerDay: 3,
});

export function rulesFromEnv(env: Env): CoinRules {
  return {
    engineVersion: env.COIN_ENGINE_VERSION?.trim() || DEFAULT_RULES.engineVersion,
    coinsPerStep: floatVar(env, "COINS_PER_STEP", DEFAULT_RULES.coinsPerStep),
    minStepsForReward: intVar(env, "MIN_STEPS_FOR_REWARD", DEFAULT_RULES.minStepsForReward),
    maxCreditedStepsPerDay: intVar(env, "MAX_CREDITED_STEPS_PER_DAY", DEFAULT_RULES.maxCreditedStepsPerDay),
    maxStepCoinsPerDay: intVar(env, "MAX_STEP_COINS_PER_DAY", DEFAULT_RULES.maxStepCoinsPerDay),
    minClaimGapSeconds: intVar(env, "MIN_CLAIM_GAP_SECONDS", DEFAULT_RULES.minClaimGapSeconds),
    maxPlausibleStepsPerMinute: DEFAULT_RULES.maxPlausibleStepsPerMinute,
    maxPlausibleStepsPerDay: DEFAULT_RULES.maxPlausibleStepsPerDay,
    minWorkoutSecondsForReward: DEFAULT_RULES.minWorkoutSecondsForReward,
    workoutBaseCoins: DEFAULT_RULES.workoutBaseCoins,
    workoutCoinsPer30Min: DEFAULT_RULES.workoutCoinsPer30Min,
    maxWorkoutCoins: DEFAULT_RULES.maxWorkoutCoins,
    maxWorkoutRewardsPerDay: DEFAULT_RULES.maxWorkoutRewardsPerDay,
  };
}

/* ------------------------------------------------------------ workout table */

const WORKOUT_MULTIPLIER: Record<string, number> = {
  WALKING: 1.0,
  RUNNING: 1.2,
  CYCLING: 1.1,
  HIKING: 1.1,
  TREADMILL: 1.1,
  ELLIPTICAL: 1.0,
  ROWING: 1.1,
  SWIMMING: 1.1,
  STAIR_CLIMBING: 1.0,
  STRENGTH: 1.0,
  BODYWEIGHT: 0.9,
  HIIT: 1.0,
  YOGA: 0.8,
  STRETCHING: 0.7,
  MOBILITY: 0.7,
  DANCE: 1.0,
  SPORT: 1.1,
  OTHER: 0.8,
};

export const KNOWN_ACTIVITY_TYPES: string[] = Object.keys(WORKOUT_MULTIPLIER);

export function isKnownActivityType(activityType: string): boolean {
  return Object.prototype.hasOwnProperty.call(WORKOUT_MULTIPLIER, activityType.toUpperCase());
}

const STREAK_MILESTONES: Record<number, number> = {
  3: 1,
  7: 3,
  14: 6,
  30: 15,
  60: 25,
  100: 40,
  365: 150,
};

/* ------------------------------------------------------- pure calculations */

/** Steps that actually earn: floor-guarded, capped, and zero below the minimum. */
export function creditedSteps(rules: CoinRules, rawSteps: number): number {
  if (rawSteps < rules.minStepsForReward) return 0;
  if (rawSteps > rules.maxCreditedStepsPerDay) return rules.maxCreditedStepsPerDay;
  return rawSteps;
}

export function coinsForSteps(rules: CoinRules, credited: number): number {
  const raw = Math.floor(credited * rules.coinsPerStep);
  return Math.max(0, Math.min(raw, rules.maxStepCoinsPerDay));
}

/** Estimated step coins for a day given the raw step count. */
export function estimateStepCoins(rules: CoinRules, rawSteps: number): number {
  return coinsForSteps(rules, creditedSteps(rules, rawSteps));
}

/** Yoga and stretching need slightly more time to count as a session. */
export function minWorkoutSeconds(rules: CoinRules, activityType: string): number {
  switch (activityType.toUpperCase()) {
    case "STRETCHING":
    case "MOBILITY":
    case "STRENGTH":
    case "BODYWEIGHT":
      return 900;
    case "YOGA":
      return 1_200;
    default:
      return rules.minWorkoutSecondsForReward;
  }
}

export function coinsForWorkout(rules: CoinRules, activityType: string, durationSeconds: number): number {
  if (durationSeconds < minWorkoutSeconds(rules, activityType)) return 0;
  const multiplier = WORKOUT_MULTIPLIER[activityType.toUpperCase()] ?? 1.0;
  const halfHours = durationSeconds / 1800.0;
  const raw = (rules.workoutBaseCoins + Math.floor(halfHours) * rules.workoutCoinsPer30Min) * multiplier;
  return Math.max(0, Math.min(Math.floor(raw), rules.maxWorkoutCoins));
}

/** Bonus for reaching (not exceeding) a streak milestone — 0 when there is none. */
export function streakBonus(streakDays: number): number {
  return STREAK_MILESTONES[streakDays] ?? 0;
}

export function isStreakMilestone(streakDays: number): boolean {
  return Object.prototype.hasOwnProperty.call(STREAK_MILESTONES, streakDays);
}

/** Next milestone above [streakDays], for "3 days to go" style copy. */
export function nextStreakMilestone(streakDays: number): { days: number; coins: number } | null {
  let best: { days: number; coins: number } | null = null;
  for (const [key, value] of Object.entries(STREAK_MILESTONES)) {
    const days = Number(key);
    if (days > streakDays && (best === null || days < best.days)) {
      best = { days, coins: value };
    }
  }
  return best;
}

/**
 * Plausibility guard applied before a claim is credited. Mirrors the Kotlin
 * implementation exactly, including its integer division: `minutes` truncates
 * to whole minutes and `steps / minutes` is integer division too.
 */
export function isPlausible(rules: CoinRules, daySteps: number, activeSeconds: number): boolean {
  if (daySteps > rules.maxPlausibleStepsPerDay) return false;
  if (daySteps <= 0) return false;
  const minutes = Math.max(Math.trunc(activeSeconds / 60), 1);
  const stepsPerMinute = Math.trunc(daySteps / minutes);
  return stepsPerMinute <= rules.maxPlausibleStepsPerMinute || minutes >= 60;
}

/** Remaining step-coin headroom for the day, given coins already awarded. */
export function remainingStepCoins(rules: CoinRules, coinsAlreadyAwarded: number): number {
  return Math.max(0, rules.maxStepCoinsPerDay - coinsAlreadyAwarded);
}
