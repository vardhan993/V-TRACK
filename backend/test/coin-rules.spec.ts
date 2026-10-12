import { describe, expect, it } from "vitest";
import {
  DEFAULT_RULES,
  coinsForSteps,
  coinsForWorkout,
  creditedSteps,
  estimateStepCoins,
  isPlausible,
  isStreakMilestone,
  minWorkoutSeconds,
  nextStreakMilestone,
  remainingStepCoins,
  streakBonus,
} from "../src/services/coinRules";

/**
 * Golden values. The *same* table is asserted by the JVM
 * `CoinRulesParityTest`, so the Kotlin copy and this copy cannot drift apart
 * without at least one suite going red.
 */
const STEP_GOLDEN: Array<[number, number]> = [
  [0, 0],
  [999, 0],
  [1000, 0],
  [1001, 0],
  [2000, 1],
  [3000, 2],
  [5000, 4],
  [8000, 7],
  [9000, 8],
  [10000, 8],
  [50000, 8],
  [100000, 8],
];

const WORKOUT_GOLDEN: Array<[string, number, number]> = [
  ["WALKING", 599, 0],
  ["WALKING", 600, 1],
  ["WALKING", 1_799, 1],
  ["WALKING", 1_800, 2],
  ["WALKING", 3_600, 3],
  ["WALKING", 5_400, 4],
  ["WALKING", 7_200, 5],
  ["WALKING", 9_000, 6],
  ["WALKING", 30_000, 6],
  ["RUNNING", 3_600, 3],
  ["CYCLING", 5_400, 4],
  ["HIKING", 7_200, 5],
  ["STRENGTH", 899, 0],
  ["STRENGTH", 900, 1],
  ["STRENGTH", 2_700, 2],
  ["BODYWEIGHT", 900, 0],
  ["BODYWEIGHT", 3_600, 2],
  ["YOGA", 1_199, 0],
  ["YOGA", 1_200, 0],
  ["YOGA", 1_800, 1],
  ["STRETCHING", 899, 0],
  ["STRETCHING", 900, 0],
  ["STRETCHING", 1_800, 1],
  ["MOBILITY", 900, 0],
  ["MOBILITY", 1_800, 1],
];

describe("coin rules — steps", () => {
  it.each(STEP_GOLDEN)("estimateStepCoins(%i) === %i", (steps, expected) => {
    expect(estimateStepCoins(DEFAULT_RULES, steps)).toBe(expected);
  });

  it("credits nothing below the floor and caps above the ceiling", () => {
    expect(creditedSteps(DEFAULT_RULES, 999)).toBe(0);
    expect(creditedSteps(DEFAULT_RULES, 1_000)).toBe(1_000);
    expect(creditedSteps(DEFAULT_RULES, 20_000)).toBe(20_000);
    expect(creditedSteps(DEFAULT_RULES, 60_000)).toBe(20_000);
  });

  it("never exceeds the daily step-coin ceiling", () => {
    expect(coinsForSteps(DEFAULT_RULES, 20_000)).toBe(8);
    expect(coinsForSteps(DEFAULT_RULES, 1_000_000)).toBe(8);
  });

  it("reports remaining headroom against the daily cap", () => {
    expect(remainingStepCoins(DEFAULT_RULES, 0)).toBe(8);
    expect(remainingStepCoins(DEFAULT_RULES, 5)).toBe(3);
    expect(remainingStepCoins(DEFAULT_RULES, 8)).toBe(0);
    expect(remainingStepCoins(DEFAULT_RULES, 99)).toBe(0);
  });
});

describe("coin rules — workouts", () => {
  it.each(WORKOUT_GOLDEN)("coinsForWorkout(%s, %is) === %i", (type, seconds, expected) => {
    expect(coinsForWorkout(DEFAULT_RULES, type, seconds)).toBe(expected);
  });

  it("uses the documented per-activity minimum durations", () => {
    expect(minWorkoutSeconds(DEFAULT_RULES, "RUNNING")).toBe(600);
    expect(minWorkoutSeconds(DEFAULT_RULES, "STRENGTH")).toBe(900);
    expect(minWorkoutSeconds(DEFAULT_RULES, "BODYWEIGHT")).toBe(900);
    expect(minWorkoutSeconds(DEFAULT_RULES, "STRETCHING")).toBe(900);
    expect(minWorkoutSeconds(DEFAULT_RULES, "MOBILITY")).toBe(900);
    expect(minWorkoutSeconds(DEFAULT_RULES, "YOGA")).toBe(1_200);
  });
});

describe("coin rules — streaks", () => {
  it.each([
    [0, 0],
    [1, 0],
    [2, 0],
    [3, 1],
    [6, 0],
    [7, 3],
    [13, 0],
    [14, 6],
    [30, 15],
    [60, 25],
    [100, 40],
    [365, 150],
  ])("streakBonus(%i) === %i", (days, expected) => {
    expect(streakBonus(days)).toBe(expected);
  });

  it("knows which days are milestones", () => {
    expect(isStreakMilestone(7)).toBe(true);
    expect(isStreakMilestone(8)).toBe(false);
  });

  it("points at the next milestone", () => {
    expect(nextStreakMilestone(0)).toEqual({ days: 3, coins: 1 });
    expect(nextStreakMilestone(3)).toEqual({ days: 7, coins: 3 });
    expect(nextStreakMilestone(100)).toEqual({ days: 365, coins: 150 });
    expect(nextStreakMilestone(365)).toBeNull();
  });
});

describe("coin rules — plausibility", () => {
  it("accepts a normal walking day", () => {
    expect(isPlausible(DEFAULT_RULES, 8_000, 4 * 3_600)).toBe(true);
  });

  it("rejects an impossible sustained pace", () => {
    // 20,000 steps in one active minute.
    expect(isPlausible(DEFAULT_RULES, 20_000, 60)).toBe(false);
  });

  it("rejects zero and over-ceiling days", () => {
    expect(isPlausible(DEFAULT_RULES, 0, 3_600)).toBe(false);
    expect(isPlausible(DEFAULT_RULES, 60_001, 3_600)).toBe(false);
  });

  it("forgives a high average once the day spans a full hour", () => {
    expect(isPlausible(DEFAULT_RULES, 15_000, 3_600)).toBe(true);
  });
});
