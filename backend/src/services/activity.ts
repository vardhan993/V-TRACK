import type { D1Database } from "@cloudflare/workers-types";
import { dayRange, isDayKey } from "../lib/time";

/**
 * Activity ingestion and server-side verification.
 *
 * The client uploads what the device actually measured. The server never
 * invents a number, but it does decide what is *reward eligible*: a day is only
 * marked verified when the payload is internally consistent and physically
 * plausible. Only `verified_steps` can ever reach the coin engine.
 */

export interface BucketUpload {
  hour: number;
  steps: number;
  distanceMeters: number;
  calories: number;
  activeSeconds: number;
}

export interface DayUpload {
  dayKey: string;
  steps: number;
  distanceMeters: number;
  calories: number;
  activeMinutes: number;
  source: string;
  zoneOffsetMinutes: number;
  buckets: BucketUpload[];
}

export interface DayRow {
  user_id: string;
  day_key: string;
  steps: number;
  verified_steps: number;
  distance_meters: number;
  calories: number;
  active_minutes: number;
  source: string;
  zone_offset_minutes: number;
  buckets_json: string;
  created_at: number;
  updated_at: number;
}

export interface Verification {
  ok: boolean;
  reason?: string;
  steps: number;
  distanceMeters: number;
  calories: number;
  activeMinutes: number;
}

const MAX_STEPS_PER_DAY = 60_000;
const MAX_STEPS_PER_HOUR = 6_000;
const MAX_CALORIES_PER_DAY = 15_000;
const MAX_ACTIVE_MINUTES_PER_DAY = 1_440;
const DEFAULT_METERS_PER_STEP = 0.714;

/** Independent plausibility pass over one uploaded day. */
export function verifyDay(day: DayUpload): Verification {
  if (!isDayKey(day.dayKey)) return { ok: false, reason: "bad_day_key", steps: 0, distanceMeters: 0, calories: 0, activeMinutes: 0 };
  if (day.steps < 0 || !Number.isFinite(day.steps)) {
    return { ok: false, reason: "negative_steps", steps: 0, distanceMeters: 0, calories: 0, activeMinutes: 0 };
  }
  if (day.steps > MAX_STEPS_PER_DAY) {
    return { ok: false, reason: "steps_over_daily_ceiling", steps: 0, distanceMeters: 0, calories: 0, activeMinutes: 0 };
  }

  let bucketSum = 0;
  for (const bucket of day.buckets) {
    if (!Number.isInteger(bucket.hour) || bucket.hour < 0 || bucket.hour > 23) {
      return { ok: false, reason: "bad_hour", steps: 0, distanceMeters: 0, calories: 0, activeMinutes: 0 };
    }
    if (bucket.steps < 0 || bucket.steps > MAX_STEPS_PER_HOUR) {
      return { ok: false, reason: "hourly_ceiling_exceeded", steps: 0, distanceMeters: 0, calories: 0, activeMinutes: 0 };
    }
    bucketSum += bucket.steps;
  }

  // Hourly buckets must not contradict the daily total. They are allowed to be
  // *lower* (Health Connect supplies the day total without hourly detail).
  const tolerance = Math.max(500, Math.round(day.steps * 0.1));
  if (day.buckets.length > 0 && bucketSum > day.steps + tolerance) {
    return { ok: false, reason: "bucket_total_mismatch", steps: 0, distanceMeters: 0, calories: 0, activeMinutes: 0 };
  }

  if (day.steps < 1000) {
    // Below the earning floor, but still stored for history and charts.
    return {
      ok: true,
      steps: day.steps,
      distanceMeters: sanitiseDistance(day.distanceMeters, day.steps),
      calories: sanitiseCalories(day.calories),
      activeMinutes: sanitiseActiveMinutes(day.activeMinutes),
    };
  }

  const distance = sanitiseDistance(day.distanceMeters, day.steps);
  const ratio = distance / day.steps;
  if (ratio < 0.25 || ratio > 1.6) {
    // Implausible stride for the reported distance: keep the steps, drop the
    // distance rather than storing a value that would corrupt pace charts.
    return {
      ok: true,
      steps: day.steps,
      distanceMeters: Number((day.steps * DEFAULT_METERS_PER_STEP).toFixed(2)),
      calories: sanitiseCalories(day.calories),
      activeMinutes: sanitiseActiveMinutes(day.activeMinutes),
      reason: "distance_rescaled",
    };
  }

  return {
    ok: true,
    steps: day.steps,
    distanceMeters: distance,
    calories: sanitiseCalories(day.calories),
    activeMinutes: sanitiseActiveMinutes(day.activeMinutes),
  };
}

function sanitiseDistance(value: number, steps: number): number {
  if (!Number.isFinite(value) || value < 0) return Number((steps * DEFAULT_METERS_PER_STEP).toFixed(2));
  return Number(value.toFixed(2));
}

function sanitiseCalories(value: number): number {
  if (!Number.isFinite(value) || value < 0) return 0;
  return Number(Math.min(value, MAX_CALORIES_PER_DAY).toFixed(2));
}

function sanitiseActiveMinutes(value: number): number {
  if (!Number.isFinite(value) || value < 0) return 0;
  return Math.min(Math.round(value), MAX_ACTIVE_MINUTES_PER_DAY);
}

export interface SyncResult {
  acceptedDays: number;
  verifiedSteps: Record<string, number>;
  rejected: string[];
}

/**
 * Upserts uploaded days. Verification decides `verified_steps`; the raw numbers
 * are always preserved so history and charts stay faithful to the device.
 */
export async function syncActivityDays(
  db: D1Database,
  userId: string,
  days: DayUpload[],
  now: number,
): Promise<SyncResult> {
  const verifiedSteps: Record<string, number> = {};
  const rejected: string[] = [];
  const statements = [];
  let accepted = 0;

  const seen = new Set<string>();
  const uniqueDays = days.filter((day) => {
    if (seen.has(day.dayKey)) return false;
    seen.add(day.dayKey);
    return true;
  });

  for (const day of uniqueDays) {
    const check = verifyDay(day);
    const storedSteps = Number.isFinite(day.steps) ? Math.max(0, Math.round(day.steps)) : 0;
    const storedVerified = check.ok ? Math.min(check.steps, storedSteps) : 0;

    if (!check.ok) rejected.push(day.dayKey);
    verifiedSteps[day.dayKey] = storedVerified;
    accepted += 1;

    statements.push(
      db.prepare(
        `INSERT INTO activity_days
           (user_id, day_key, steps, verified_steps, distance_meters, calories, active_minutes,
            source, zone_offset_minutes, buckets_json, created_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?11)
         ON CONFLICT(user_id, day_key) DO UPDATE SET
           steps = excluded.steps,
           verified_steps = excluded.verified_steps,
           distance_meters = excluded.distance_meters,
           calories = excluded.calories,
           active_minutes = excluded.active_minutes,
           source = excluded.source,
           zone_offset_minutes = excluded.zone_offset_minutes,
           buckets_json = excluded.buckets_json,
           updated_at = excluded.updated_at`,
      ).bind(
        userId,
        day.dayKey,
        storedSteps,
        storedVerified,
        check.distanceMeters,
        check.calories,
        check.activeMinutes,
        day.source.slice(0, 24),
        day.zoneOffsetMinutes,
        JSON.stringify(day.buckets.slice(0, 24)),
        now,
      ),
    );

    statements.push(
      db.prepare(
        `UPDATE users SET timezone_offset_minutes = ?1, updated_at = ?2 WHERE id = ?3 AND timezone_offset_minutes != ?1`,
      ).bind(day.zoneOffsetMinutes, now, userId),
    );
  }

  if (statements.length > 0) await db.batch(statements);
  return { acceptedDays: accepted, verifiedSteps, rejected };
}

export async function listActivityDays(
  db: D1Database,
  userId: string,
  from: string,
  to: string,
): Promise<DayRow[]> {
  const days = dayRange(from, to, 400);
  if (days.length === 0) return [];
  const placeholders = days.map((_, index) => `?${index + 2}`).join(", ");
  const rows = await db
    .prepare(
      `SELECT * FROM activity_days WHERE user_id = ?1 AND day_key IN (${placeholders}) ORDER BY day_key ASC`,
    )
    .bind(userId, ...days)
    .all<DayRow>();
  return rows.results ?? [];
}

export async function getActivityDay(db: D1Database, userId: string, dayKey: string): Promise<DayRow | null> {
  return db
    .prepare(`SELECT * FROM activity_days WHERE user_id = ?1 AND day_key = ?2`)
    .bind(userId, dayKey)
    .first<DayRow>();
}

/* ----------------------------------------------------------------- metrics */

export interface PeriodMetrics {
  steps: number;
  activeMinutes: number;
  workoutCount: number;
  workoutMinutes: number;
  workoutDistanceMeters: number;
  longestWorkoutMeters: number;
  sets: number;
  daysActive: number;
}

/** Aggregates used by challenges, achievements and the coach. */
export async function metricsForRange(
  db: D1Database,
  userId: string,
  fromDayKey: string,
  toDayKey: string,
): Promise<PeriodMetrics> {
  const activity = await db
    .prepare(
      `SELECT
         COALESCE(SUM(steps), 0) AS steps,
         COALESCE(SUM(active_minutes), 0) AS active_minutes,
         COUNT(*) AS days_active
       FROM activity_days
       WHERE user_id = ?1 AND day_key >= ?2 AND day_key <= ?3
         AND (steps >= 1000 OR active_minutes >= 20)`,
    )
    .bind(userId, fromDayKey, toDayKey)
    .first<{ steps: number; active_minutes: number; days_active: number }>();

  const workouts = await db
    .prepare(
      `SELECT
         COUNT(*) AS workout_count,
         COALESCE(SUM(duration_seconds), 0) AS workout_seconds,
         COALESCE(SUM(distance_meters), 0) AS workout_distance,
         COALESCE(MAX(distance_meters), 0) AS longest
       FROM workouts
       WHERE user_id = ?1 AND day_key >= ?2 AND day_key <= ?3`,
    )
    .bind(userId, fromDayKey, toDayKey)
    .first<{ workout_count: number; workout_seconds: number; workout_distance: number; longest: number }>();

  const sets = await db
    .prepare(
      `SELECT COUNT(*) AS sets FROM workout_sets
       WHERE user_id = ?1 AND completed_at >= ?4 AND completed_at < ?5`,
    )
    .bind(userId, fromDayKey, toDayKey, dayStartMillis(fromDayKey), dayStartMillis(toDayKey) + 86_400_000)
    .first<{ sets: number }>();

  return {
    steps: activity?.steps ?? 0,
    activeMinutes: activity?.active_minutes ?? 0,
    daysActive: activity?.days_active ?? 0,
    workoutCount: workouts?.workout_count ?? 0,
    workoutMinutes: Math.round((workouts?.workout_seconds ?? 0) / 60),
    workoutDistanceMeters: workouts?.workout_distance ?? 0,
    longestWorkoutMeters: workouts?.longest ?? 0,
    sets: sets?.sets ?? 0,
  };
}

export function dayStartMillis(dayKey: string): number {
  return Date.parse(`${dayKey}T00:00:00Z`);
}
