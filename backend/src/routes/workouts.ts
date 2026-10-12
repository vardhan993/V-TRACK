import { z } from "zod";
import { ApiError, jsonResponse } from "../lib/http";
import type { Router } from "../lib/router";
import { parseOrThrow } from "../lib/validate";
import {
  listWorkouts,
  syncWorkouts,
  toWorkoutDto,
  updateWorkout,
  type WorkoutUpload,
} from "../services/workouts";
import { principalOf, readJson, intQuery } from "./helpers";

const setSchema = z.object({
  clientId: z.string().min(4).max(120),
  exerciseId: z.string().min(1).max(64),
  setIndex: z.number().int().min(0).max(500),
  reps: z.number().int().min(0).max(1_000).nullable().optional(),
  weightKg: z.number().min(0).max(1_000).nullable().optional(),
  durationSeconds: z.number().int().min(0).max(86_400).nullable().optional(),
  restSeconds: z.number().int().min(0).max(86_400).nullable().optional(),
  rpe: z.number().min(1).max(10).nullable().optional(),
  isWarmup: z.boolean().optional(),
  completedAt: z.number().int().min(0),
});

const workoutSchema = z.object({
  clientId: z.string().min(6, "Missing workout id").max(120),
  activityType: z.string().trim().min(1).max(32),
  title: z.string().max(120).nullable().optional(),
  startedAt: z.number().int().min(0),
  endedAt: z.number().int().min(0),
  durationSeconds: z.number().int().min(0).max(86_400),
  distanceMeters: z.number().min(0).max(500_000),
  calories: z.number().min(0).max(30_000),
  avgPaceSecPerKm: z.number().min(0).max(100_000).optional(),
  avgSpeedKmh: z.number().min(0).max(300).optional(),
  steps: z.number().int().min(0).max(200_000).optional(),
  elevationGainMeters: z.number().min(0).max(30_000).optional(),
  indoor: z.boolean().optional(),
  manual: z.boolean().optional(),
  notes: z.string().max(2_000).nullable().optional(),
  perceivedEffort: z.number().min(1).max(10).nullable().optional(),
  sets: z.array(setSchema).max(200, "At most 200 sets per session").optional(),
});

const syncSchema = z.array(workoutSchema).min(1, "Send at least one workout").max(25, "Sync at most 25 workouts at a time");

const patchSchema = z.object({
  notes: z.string().max(2_000).nullable().optional(),
  perceivedEffort: z.number().min(1).max(10).nullable().optional(),
});

export function registerWorkoutRoutes(router: Router): void {
  router.post("/v1/workouts/sync", async (ctx) => {
    const principal = principalOf(ctx);
    const uploads = parseOrThrow(syncSchema, await readJson(ctx)) as unknown as WorkoutUpload[];
    const result = await syncWorkouts(ctx.db, principal.userId, uploads, ctx.now, principal.timezoneOffsetMinutes);
    return jsonResponse({
      accepted: result.accepted,
      duplicates: result.duplicates,
      workouts: result.workouts.map(toWorkoutDto),
    });
  });

  router.get("/v1/workouts", async (ctx) => {
    const principal = principalOf(ctx);
    const limit = intQuery(ctx, "limit", 50, 1, 100);
    const cursorRaw = ctx.query.get("cursor");
    const cursor = cursorRaw ? decodeWorkoutCursor(cursorRaw) : null;
    if (cursorRaw && !cursor) throw ApiError.badRequest("validation_error", "cursor is not valid.");

    const rows = await listWorkouts(ctx.db, principal.userId, limit, cursor);
    return jsonResponse(rows.map(toWorkoutDto));
  });

  router.patch("/v1/workouts/:clientId", async (ctx) => {
    const principal = principalOf(ctx);
    const patch = parseOrThrow(patchSchema, await readJson(ctx));
    const clientId = ctx.params.clientId ?? "";
    if (clientId.length === 0) throw ApiError.notFound("No such workout.");

    const row = await updateWorkout(ctx.db, principal.userId, clientId, patch, ctx.now);
    if (!row) throw ApiError.notFound("No such workout.");
    return jsonResponse(toWorkoutDto(row));
  });
}

function decodeWorkoutCursor(raw: string): { startedAt: number; id: string } | null {
  try {
    const padded = raw.replace(/-/g, "+").replace(/_/g, "/");
    const decoded = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
    const separator = decoded.lastIndexOf(":");
    if (separator <= 0) return null;
    const startedAt = Number.parseInt(decoded.slice(0, separator), 10);
    const id = decoded.slice(separator + 1);
    if (!Number.isFinite(startedAt) || id.length === 0) return null;
    return { startedAt, id };
  } catch {
    return null;
  }
}
