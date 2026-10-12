import { z } from "zod";
import { ApiError, emptyResponse, jsonResponse } from "../lib/http";
import type { Router } from "../lib/router";
import { parseOrThrow } from "../lib/validate";
import { findUserById, toUserDto, updateProfile, type ProfilePatch } from "../services/users";
import { KNOWN_ACTIVITY_TYPES } from "../services/coinRules";
import { readJson, principalOf } from "./helpers";

const HEX_EMOJI = z.string().max(16, "That emoji is too long");

const patchSchema = z
  .object({
    displayName: z.string().trim().min(1, "Cannot be blank").max(80, "That name is too long").optional(),
    avatarEmoji: HEX_EMOJI.optional(),
    birthYear: z.number().int().min(1900, "Enter a valid birth year").max(new Date().getFullYear(), "That year is in the future").optional(),
    sex: z.string().trim().max(16).optional(),
    heightCm: z.number().min(50, "Height looks wrong").max(300, "Height looks wrong").optional(),
    weightKg: z.number().min(20, "Weight looks wrong").max(400, "Weight looks wrong").optional(),
    fitnessLevel: z.enum(["BEGINNER", "INTERMEDIATE", "ADVANCED"]).optional(),
    primaryGoal: z.enum(["LOSE_WEIGHT", "BUILD_MUSCLE", "ENDURANCE", "STAY_ACTIVE"]).optional(),
    preferredActivities: z.array(z.string().max(32)).max(20, "Pick at most 20 activities").optional(),
    weeklyWorkoutTarget: z.number().int().min(0, "Cannot be negative").max(14, "At most 14 sessions a week").optional(),
    unitSystem: z.enum(["METRIC", "IMPERIAL"]).optional(),
    stepGoal: z.number().int().min(1000, "Minimum step goal is 1,000").max(50000, "Maximum step goal is 50,000").optional(),
    onboardingComplete: z.boolean().optional(),
    healthConnectEnabled: z.boolean().optional(),
    shareRoutes: z.boolean().optional(),
    shareActivityWithCoach: z.boolean().optional(),
  })
  // Not `.strict()`: a newer app may add a field this build does not know yet,
  // and the wire contract says unknown keys are ignored rather than rejected.
  ;

export function registerMeRoutes(router: Router): void {
  router.get("/v1/me", async (ctx) => {
    const user = await findUserById(ctx.db, principalOf(ctx).userId);
    if (!user) throw ApiError.unauthorized();
    return jsonResponse(toUserDto(user));
  });

  router.patch("/v1/me", async (ctx) => {
    const input = parseOrThrow(patchSchema, await readJson(ctx));
    const principal = principalOf(ctx);

    // Only activity types the coin engine actually knows are accepted.
    if (input.preferredActivities) {
      const known = new Set(KNOWN_ACTIVITY_TYPES);
      const unknown = input.preferredActivities.filter((value) => !known.has(value.toUpperCase()));
      if (unknown.length > 0) {
        throw ApiError.badRequest("validation_error", `preferredActivities: unknown activity type "${unknown[0]}"`);
      }
    }

    const patch: ProfilePatch = { ...input };
    const updated = await updateProfile(ctx.db, principal.userId, patch, ctx.now);
    if (!updated) throw ApiError.unauthorized();
    return jsonResponse(toUserDto(updated));
  });

  router.delete("/v1/me", async (ctx) => {
    const principal = principalOf(ctx);
    // Children reference users with ON DELETE CASCADE, so this one statement
    // removes the profile, activity, workouts, ledger rows, tokens and plans.
    await ctx.db.prepare(`DELETE FROM users WHERE id = ?1`).bind(principal.userId).run();
    return emptyResponse();
  });

  router.get("/v1/me/export", async (ctx) => {
    const principal = principalOf(ctx);
    const profile = await findUserById(ctx.db, principal.userId);
    if (!profile) throw ApiError.unauthorized();

    const activity = await ctx.db
      .prepare(
        `SELECT day_key, steps, verified_steps, distance_meters, calories, active_minutes, updated_at
         FROM activity_days WHERE user_id = ?1 ORDER BY day_key ASC LIMIT 5000`,
      )
      .bind(principal.userId)
      .all<{
        day_key: string;
        steps: number;
        verified_steps: number;
        distance_meters: number;
        calories: number;
        active_minutes: number;
        updated_at: number;
      }>();

    const workouts = await ctx.db
      .prepare(`SELECT * FROM workouts WHERE user_id = ?1 ORDER BY started_at ASC LIMIT 5000`)
      .bind(principal.userId)
      .all<{
        id: string;
        client_id: string;
        activity_type: string;
        title: string | null;
        started_at: number;
        ended_at: number;
        duration_seconds: number;
        distance_meters: number;
        calories: number;
        steps: number;
        verified: number;
        coins_awarded: number;
        created_at: number;
      }>();

    const transactions = await ctx.db
      .prepare(
        `SELECT id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at
         FROM coin_transactions WHERE user_id = ?1 ORDER BY created_at ASC LIMIT 5000`,
      )
      .bind(principal.userId)
      .all<{
        id: string;
        client_event_id: string;
        type: string;
        amount: number;
        reason: string;
        activity_ref: string | null;
        status: string;
        balance_after: number | null;
        created_at: number;
      }>();

    return jsonResponse({
      exportedAt: ctx.now,
      profile: toUserDto(profile),
      activity: (activity.results ?? []).map((row) => ({
        dayKey: row.day_key,
        steps: row.steps,
        verifiedSteps: row.verified_steps,
        distanceMeters: row.distance_meters,
        calories: row.calories,
        activeMinutes: row.active_minutes,
        updatedAt: row.updated_at,
      })),
      workouts: (workouts.results ?? []).map((row) => ({
        id: row.id,
        clientId: row.client_id,
        activityType: row.activity_type,
        title: row.title,
        startedAt: row.started_at,
        endedAt: row.ended_at,
        durationSeconds: row.duration_seconds,
        distanceMeters: row.distance_meters,
        calories: row.calories,
        steps: row.steps,
        verified: row.verified === 1,
        coinsAwarded: row.coins_awarded,
        createdAt: row.created_at,
      })),
      transactions: (transactions.results ?? []).map((row) => ({
        id: row.id,
        clientEventId: row.client_event_id,
        type: row.type,
        amount: row.amount,
        reason: row.reason,
        activityRef: row.activity_ref,
        status: row.status,
        balanceAfter: row.balance_after,
        createdAt: row.created_at,
      })),
    });
  });
}
