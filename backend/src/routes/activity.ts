import { z } from "zod";
import { ApiError, jsonResponse } from "../lib/http";
import type { Router } from "../lib/router";
import { dayKeySchema, offsetMinutesSchema, parseOrThrow } from "../lib/validate";
import { rulesFromEnv, coinsForWorkout, minWorkoutSeconds, isPlausible, type CoinRules } from "../services/coinRules";
import { claimSteps, claimWorkout, noRewardOutcome, type ClaimOutcome } from "../services/coins";
import { listActivityDays, syncActivityDays } from "../services/activity";
import { getWorkoutByClientId, type WorkoutRow } from "../services/workouts";
import { updateProfile } from "../services/users";
import { principalOf, readJson } from "./helpers";

const bucketSchema = z.object({
  hour: z.number().int().min(0).max(23),
  steps: z.number().int().min(0).max(20_000),
  distanceMeters: z.number().min(0).max(100_000),
  calories: z.number().min(0).max(10_000),
  activeSeconds: z.number().int().min(0).max(86_400),
});

const daySchema = z.object({
  dayKey: dayKeySchema,
  steps: z.number().int().min(0, "Steps cannot be negative").max(60_000, "That is more than 60,000 steps"),
  distanceMeters: z.number().min(0).max(200_000),
  calories: z.number().min(0).max(20_000),
  activeMinutes: z.number().int().min(0).max(1_440),
  source: z.string().trim().min(1).max(24),
  zoneOffsetMinutes: offsetMinutesSchema,
  buckets: z.array(bucketSchema).max(24, "At most 24 hourly buckets").default([]),
});

const syncSchema = z.object({ days: z.array(daySchema).min(1, "Send at least one day").max(60, "Sync at most 60 days at a time") });

const claimStepsSchema = z.object({
  clientEventId: z.string().min(8, "Missing client event id").max(200),
  dayKey: dayKeySchema,
  steps: z.number().int().min(0).max(60_000),
  activeSeconds: z.number().int().min(0).max(172_800),
  source: z.string().trim().min(1).max(24),
  zoneOffsetMinutes: offsetMinutesSchema,
});

const claimWorkoutSchema = z.object({
  clientEventId: z.string().min(8, "Missing client event id").max(200),
  workoutClientId: z.string().min(6, "Missing workout id").max(200),
});

function claimResponse(outcome: ClaimOutcome) {
  return {
    transactionId: outcome.transactionId,
    clientEventId: outcome.clientEventId,
    amount: outcome.amount,
    status: outcome.status,
    balance: outcome.balance,
    reason: outcome.reason,
    duplicate: outcome.duplicate,
  };
}

export function registerActivityRoutes(router: Router): void {
  router.post("/v1/activity/sync", async (ctx) => {
    const principal = principalOf(ctx);
    const input = parseOrThrow(syncSchema, await readJson(ctx));
    const result = await syncActivityDays(ctx.db, principal.userId, input.days, ctx.now);
    return jsonResponse(result);
  });

  router.get("/v1/activity/days", async (ctx) => {
    const principal = principalOf(ctx);
    const from = ctx.query.get("from");
    const to = ctx.query.get("to");
    if (!from || !to) throw ApiError.badRequest("validation_error", "from and to are required.");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      throw ApiError.badRequest("validation_error", "Expected YYYY-MM-DD day keys.");
    }
    const rows = await listActivityDays(ctx.db, principal.userId, from, to);
    return jsonResponse(
      rows.map((row) => ({
        dayKey: row.day_key,
        steps: row.steps,
        verifiedSteps: row.verified_steps,
        distanceMeters: row.distance_meters,
        calories: row.calories,
        activeMinutes: row.active_minutes,
        updatedAt: row.updated_at,
      })),
    );
  });

  router.post("/v1/activity/claim/steps", async (ctx) => {
    const principal = principalOf(ctx);
    const input = parseOrThrow(claimStepsSchema, await readJson(ctx));
    const rules = rulesFromEnv(ctx.env);

    if (input.zoneOffsetMinutes !== principal.timezoneOffsetMinutes) {
      await updateProfile(ctx.db, principal.userId, { timezoneOffsetMinutes: input.zoneOffsetMinutes }, ctx.now);
    }

    // Plausibility is evaluated before the ledger is touched: an implausible
    // payload is answered with a NO_REWARD outcome rather than a 4xx, because
    // the app treats that as "nothing to credit" and keeps its own history.
    if (!isPlausible(rules, input.steps, input.activeSeconds)) {
      const outcome = await noRewardOutcome(
        ctx.db,
        principal.userId,
        input.clientEventId,
        `That step count does not look plausible for ${Math.round(input.activeSeconds / 60)} active minutes.`,
      );
      return jsonResponse(claimResponse(outcome));
    }

    const outcome = await claimSteps(ctx.db, {
      userId: principal.userId,
      clientEventId: input.clientEventId,
      dayKey: input.dayKey,
      steps: input.steps,
      activeSeconds: input.activeSeconds,
      now: ctx.now,
      minClaimGapSeconds: rules.minClaimGapSeconds,
      maxCreditedStepsPerDay: rules.maxCreditedStepsPerDay,
      maxStepCoinsPerDay: rules.maxStepCoinsPerDay,
      coinsPerStep: rules.coinsPerStep,
      minStepsForReward: rules.minStepsForReward,
    });
    return jsonResponse(claimResponse(outcome));
  });

  router.post("/v1/activity/claim/workout", async (ctx) => {
    const principal = principalOf(ctx);
    const input = parseOrThrow(claimWorkoutSchema, await readJson(ctx));
    const rules = rulesFromEnv(ctx.env);

    const workout = await getWorkoutByClientId(ctx.db, principal.userId, input.workoutClientId);
    if (!workout) {
      const outcome = await noRewardOutcome(
        ctx.db,
        principal.userId,
        input.clientEventId,
        "That workout has not been synced yet — sync your history first.",
      );
      return jsonResponse(claimResponse(outcome));
    }

    // The ledger key is derived from the workout, not from whatever id the
    // caller sent: a session can be rewarded exactly once, no matter how many
    // times (or with how many random ids) the claim is retried.
    const outcome = creditWorkout(ctx, rules, workout, `workout:${workout.client_id}`);
    return jsonResponse(claimResponse(await outcome));
  });
}

/** Derives the workout amount from the stored row — the client never sends one. */
function creditWorkout(
  ctx: { db: import("@cloudflare/workers-types").D1Database; now: number },
  rules: CoinRules,
  workout: WorkoutRow,
  clientEventId: string,
): Promise<ClaimOutcome> {
  const minimum = minWorkoutSeconds(rules, workout.activity_type);

  if (workout.duration_seconds < minimum) {
    return noRewardOutcome(
      ctx.db,
      workout.user_id,
      clientEventId,
      `${workout.activity_type} sessions need at least ${Math.round(minimum / 60)} minutes to earn a reward.`,
    );
  }

  const amount = coinsForWorkout(rules, workout.activity_type, workout.duration_seconds);
  if (amount <= 0) {
    return noRewardOutcome(ctx.db, workout.user_id, clientEventId, "This session does not earn any V-Coins.");
  }

  return claimWorkout(ctx.db, {
    userId: workout.user_id,
    clientEventId,
    workoutClientId: workout.client_id,
    dayKey: workout.day_key,
    now: ctx.now,
    amount,
    reason: `Workout reward — ${workout.activity_type.toLowerCase()} ${Math.round(workout.duration_seconds / 60)} min`,
    maxWorkoutRewardsPerDay: rules.maxWorkoutRewardsPerDay,
  });
}
