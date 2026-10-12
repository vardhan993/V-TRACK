import type { D1Database } from "@cloudflare/workers-types";
import { newId } from "../lib/ids";
import { dayKeyAt } from "../lib/time";
import { isKnownActivityType } from "./coinRules";

/** Workout ingestion, history and per-workout edits. */

export interface StrengthSetUpload {
  clientId: string;
  exerciseId: string;
  setIndex: number;
  reps: number | null;
  weightKg: number | null;
  durationSeconds: number | null;
  restSeconds: number | null;
  rpe: number | null;
  isWarmup: boolean;
  completedAt: number;
}

export interface WorkoutUpload {
  clientId: string;
  activityType: string;
  title: string | null;
  startedAt: number;
  endedAt: number;
  durationSeconds: number;
  distanceMeters: number;
  calories: number;
  avgPaceSecPerKm: number;
  avgSpeedKmh: number;
  steps: number;
  elevationGainMeters: number;
  indoor: boolean;
  manual: boolean;
  notes: string | null;
  perceivedEffort: number | null;
  sets: StrengthSetUpload[];
}

export interface WorkoutRow {
  id: string;
  user_id: string;
  client_id: string;
  activity_type: string;
  title: string | null;
  started_at: number;
  ended_at: number;
  duration_seconds: number;
  distance_meters: number;
  calories: number;
  avg_pace_sec_per_km: number;
  avg_speed_kmh: number;
  steps: number;
  elevation_gain_meters: number;
  indoor: number;
  manual: number;
  notes: string | null;
  perceived_effort: number | null;
  verified: number;
  coins_awarded: number;
  day_key: string;
  created_at: number;
  updated_at: number;
}

export interface WorkoutDto {
  id: string;
  clientId: string | null;
  activityType: string;
  title: string | null;
  startedAt: number;
  endedAt: number;
  durationSeconds: number;
  distanceMeters: number;
  calories: number;
  steps: number;
  verified: boolean;
  coinsAwarded: number;
  notes: string | null;
  perceivedEffort: number | null;
  createdAt: number;
}

export function toWorkoutDto(row: WorkoutRow): WorkoutDto {
  return {
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
    notes: row.notes,
    perceivedEffort: row.perceived_effort,
    createdAt: row.created_at,
  };
}

const MAX_DURATION_SECONDS = 86_400;

function clampNumber(value: number, min: number, max: number, fallback: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.max(min, Math.min(max, value));
}

export interface SyncWorkoutsResult {
  accepted: number;
  duplicates: number;
  workouts: WorkoutRow[];
}

export async function syncWorkouts(
  db: D1Database,
  userId: string,
  uploads: WorkoutUpload[],
  now: number,
  offsetMinutes = 0,
): Promise<SyncWorkoutsResult> {
  let accepted = 0;
  let duplicates = 0;
  const rows: WorkoutRow[] = [];

  for (const upload of uploads.slice(0, 25)) {
    const activityType = upload.activityType.toUpperCase();
    if (!isKnownActivityType(activityType)) continue;

    const existing = await db
      .prepare(`SELECT id FROM workouts WHERE user_id = ?1 AND client_id = ?2`)
      .bind(userId, upload.clientId)
      .first<{ id: string }>();

    const serverId = existing?.id ?? newId();
    const startedAt = Math.max(0, Math.round(upload.startedAt));
    const duration = Math.round(clampNumber(upload.durationSeconds, 0, MAX_DURATION_SECONDS, 0));
    const endedAt = Math.max(startedAt, Math.round(upload.endedAt) || startedAt + duration * 1000);
    const dayKey = dayKeyAt(startedAt, offsetMinutes);

    const results = await db.batch([
      db.prepare(
        `INSERT INTO workouts
           (id, user_id, client_id, activity_type, title, started_at, ended_at, duration_seconds,
            distance_meters, calories, avg_pace_sec_per_km, avg_speed_kmh, steps, elevation_gain_meters,
            indoor, manual, notes, perceived_effort, verified, coins_awarded, day_key, created_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, 1, 0, ?19, ?20, ?20)
         ON CONFLICT(user_id, client_id) DO UPDATE SET
           activity_type = excluded.activity_type,
           title = excluded.title,
           started_at = excluded.started_at,
           ended_at = excluded.ended_at,
           duration_seconds = excluded.duration_seconds,
           distance_meters = excluded.distance_meters,
           calories = excluded.calories,
           avg_pace_sec_per_km = excluded.avg_pace_sec_per_km,
           avg_speed_kmh = excluded.avg_speed_kmh,
           steps = excluded.steps,
           elevation_gain_meters = excluded.elevation_gain_meters,
           indoor = excluded.indoor,
           manual = excluded.manual,
           notes = excluded.notes,
           perceived_effort = excluded.perceived_effort,
           day_key = excluded.day_key,
           updated_at = excluded.updated_at`,
      ).bind(
        serverId, userId, upload.clientId, activityType,
        upload.title?.slice(0, 120) ?? null,
        startedAt, endedAt, duration,
        clampNumber(upload.distanceMeters, 0, 500_000, 0),
        clampNumber(upload.calories, 0, 30_000, 0),
        clampNumber(upload.avgPaceSecPerKm, 0, 100_000, 0),
        clampNumber(upload.avgSpeedKmh, 0, 300, 0),
        Math.round(clampNumber(upload.steps, 0, 200_000, 0)),
        clampNumber(upload.elevationGainMeters, 0, 30_000, 0),
        upload.indoor ? 1 : 0,
        upload.manual ? 1 : 0,
        upload.notes?.slice(0, 2_000) ?? null,
        upload.perceivedEffort === null ? null : clampNumber(upload.perceivedEffort, 1, 10, 5),
        dayKey,
        now,
      ),
      // Sets replace the previous upload for this workout so a re-sync cannot
      // duplicate them (the client id is the idempotency key).
      db.prepare(`DELETE FROM workout_sets WHERE workout_id = ?1`).bind(serverId),
    ]);

    for (const set of upload.sets.slice(0, 200)) {
      results.push(
        await db
          .prepare(
            `INSERT INTO workout_sets
               (id, workout_id, user_id, client_id, exercise_id, set_index, reps, weight_kg,
                duration_seconds, rest_seconds, rpe, is_warmup, completed_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
             ON CONFLICT(workout_id, client_id) DO UPDATE SET
               exercise_id = excluded.exercise_id,
               set_index = excluded.set_index,
               reps = excluded.reps,
               weight_kg = excluded.weight_kg,
               duration_seconds = excluded.duration_seconds,
               rest_seconds = excluded.rest_seconds,
               rpe = excluded.rpe,
               is_warmup = excluded.is_warmup,
               completed_at = excluded.completed_at`,
          )
          .bind(
            newId(), serverId, userId, set.clientId, set.exerciseId.slice(0, 64),
            Math.max(0, Math.round(set.setIndex)),
            set.reps === null ? null : Math.max(0, Math.round(set.reps)),
            set.weightKg === null ? null : clampNumber(set.weightKg, 0, 1_000, 0),
            set.durationSeconds === null ? null : Math.max(0, Math.round(set.durationSeconds)),
            set.restSeconds === null ? null : Math.max(0, Math.round(set.restSeconds)),
            set.rpe === null ? null : clampNumber(set.rpe, 1, 10, 5),
            set.isWarmup ? 1 : 0,
            Math.round(set.completedAt),
          )
          .run(),
      );
    }

    for (const result of results) {
      if (!result.success) throw new Error(result.error ?? "workout sync failed");
    }

    if (existing) duplicates += 1;
    else accepted += 1;

    const row = await db
      .prepare(`SELECT * FROM workouts WHERE user_id = ?1 AND client_id = ?2`)
      .bind(userId, upload.clientId)
      .first<WorkoutRow>();
    if (row) rows.push(row);
  }

  return { accepted, duplicates, workouts: rows };
}

export async function getWorkoutByClientId(
  db: D1Database,
  userId: string,
  clientId: string,
): Promise<WorkoutRow | null> {
  return db
    .prepare(`SELECT * FROM workouts WHERE user_id = ?1 AND client_id = ?2`)
    .bind(userId, clientId)
    .first<WorkoutRow>();
}

export async function listWorkouts(
  db: D1Database,
  userId: string,
  limit: number,
  cursor: { startedAt: number; id: string } | null,
): Promise<WorkoutRow[]> {
  const capped = Math.max(1, Math.min(limit, 100));
  if (cursor) {
    const rows = await db
      .prepare(
        `SELECT * FROM workouts
         WHERE user_id = ?1 AND (started_at < ?2 OR (started_at = ?2 AND id < ?3))
         ORDER BY started_at DESC, id DESC LIMIT ?4`,
      )
      .bind(userId, cursor.startedAt, cursor.id, capped)
      .all<WorkoutRow>();
    return rows.results ?? [];
  }
  const rows = await db
    .prepare(
      `SELECT * FROM workouts WHERE user_id = ?1 ORDER BY started_at DESC, id DESC LIMIT ?2`,
    )
    .bind(userId, capped)
    .all<WorkoutRow>();
  return rows.results ?? [];
}

export async function updateWorkout(
  db: D1Database,
  userId: string,
  clientId: string,
  patch: { notes?: string | null; perceivedEffort?: number | null },
  now: number,
): Promise<WorkoutRow | null> {
  const assignments: string[] = [];
  const binds: unknown[] = [];

  if (patch.notes !== undefined) {
    binds.push(patch.notes === null ? null : patch.notes.slice(0, 2_000));
    assignments.push(`notes = ?${binds.length}`);
  }
  if (patch.perceivedEffort !== undefined) {
    binds.push(patch.perceivedEffort === null ? null : clampNumber(patch.perceivedEffort, 1, 10, 5));
    assignments.push(`perceived_effort = ?${binds.length}`);
  }

  if (assignments.length > 0) {
    binds.push(now);
    assignments.push(`updated_at = ?${binds.length}`);
    binds.push(userId, clientId);
    await db
      .prepare(
        `UPDATE workouts SET ${assignments.join(", ")} WHERE user_id = ?${binds.length - 1} AND client_id = ?${binds.length}`,
      )
      .bind(...binds)
      .run();
  }

  return getWorkoutByClientId(db, userId, clientId);
}
