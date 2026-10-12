import type { D1Database } from "@cloudflare/workers-types";
import { addDays } from "../lib/time";
import { creditFixed, type ClaimOutcome } from "./coins";

/**
 * Achievements: long-horizon milestones evaluated from server-side aggregates.
 *
 * Progress is derived from stored activity and workout rows only. Unlocking
 * writes one `achievement_unlocks` row (primary key on user + achievement, so it
 * happens at most once) and credits its bonus inside the same transaction.
 */

export interface AchievementRow {
  code: string;
  title: string;
  description: string;
  icon: string;
  category: string;
  metric: string;
  threshold: number;
  reward_coins: number;
  sort_order: number;
}

export interface AchievementDto {
  id: string;
  code: string;
  title: string;
  description: string;
  icon: string;
  category: string;
  threshold: number;
  progress: number;
  unlockedAt: number | null;
}

export interface Metrics {
  totalSteps: number;
  bestDaySteps: number;
  totalActiveMinutes: number;
  totalWorkouts: number;
  totalSets: number;
  longestWorkoutMeters: number;
  currentStreak: number;
  bestStreak: number;
}

/**
 * Streak rules identical to the app: a day counts when it meets the step goal
 * or logs at least 20 active minutes, and today (still in progress) never
 * breaks the run.
 */
export async function computeMetrics(db: D1Database, userId: string): Promise<Metrics> {
  const totals = await db
    .prepare(
      `SELECT COALESCE(SUM(steps), 0) AS total_steps,
              COALESCE(MAX(steps), 0) AS best_day_steps,
              COALESCE(SUM(active_minutes), 0) AS total_minutes
       FROM activity_days WHERE user_id = ?1`,
    )
    .bind(userId)
    .first<{ total_steps: number; best_day_steps: number; total_minutes: number }>();

  const workouts = await db
    .prepare(
      `SELECT COUNT(*) AS total, COALESCE(MAX(distance_meters), 0) AS longest
       FROM workouts WHERE user_id = ?1`,
    )
    .bind(userId)
    .first<{ total: number; longest: number }>();

  const sets = await db
    .prepare(`SELECT COUNT(*) AS total FROM workout_sets WHERE user_id = ?1`)
    .bind(userId)
    .first<{ total: number }>();

  const qualifiedRows = await db
    .prepare(
      `SELECT day_key FROM activity_days
       WHERE user_id = ?1
         AND (steps >= (SELECT step_goal FROM users WHERE id = ?1) OR active_minutes >= 20)
       ORDER BY day_key DESC LIMIT 365`,
    )
    .bind(userId)
    .all<{ day_key: string }>();

  const qualified = new Set((qualifiedRows.results ?? []).map((row) => row.day_key));
  const { current, best } = streaks(qualified);

  return {
    totalSteps: totals?.total_steps ?? 0,
    bestDaySteps: totals?.best_day_steps ?? 0,
    totalActiveMinutes: totals?.total_minutes ?? 0,
    totalWorkouts: workouts?.total ?? 0,
    totalSets: sets?.total ?? 0,
    longestWorkoutMeters: workouts?.longest ?? 0,
    currentStreak: current,
    bestStreak: best,
  };
}

export function streaks(qualifiedDayKeys: Set<string>, todayKey?: string): { current: number; best: number } {
  const sorted = [...qualifiedDayKeys].sort();
  if (sorted.length === 0) return { current: 0, best: 0 };

  let best = 1;
  let run = 1;
  for (let i = 1; i < sorted.length; i++) {
    const previous = Date.parse(`${sorted[i - 1]}T00:00:00Z`);
    const now = Date.parse(`${sorted[i]}T00:00:00Z`);
    run = now - previous === 86_400_000 ? run + 1 : 1;
    if (run > best) best = run;
  }

  let current = 0;
  const latest = sorted[sorted.length - 1] as string;
  const today = todayKey ?? new Date().toISOString().slice(0, 10);
  // Today in progress must not break the streak: if the last qualified day is
  // today or yesterday, count back from there.
  const yesterday = addDays(today, -1);
  if (latest === today || latest === yesterday) {
    let cursor = latest;
    while (qualifiedDayKeys.has(cursor)) {
      current += 1;
      cursor = addDays(cursor, -1);
    }
  }

  return { current, best: Math.max(best, current) };
}

function progressFor(metric: string, metrics: Metrics): number {
  switch (metric) {
    case "TOTAL_STEPS":
      return metrics.totalSteps;
    case "BEST_DAY_STEPS":
      return metrics.bestDaySteps;
    case "WORKOUT_COUNT":
      return metrics.totalWorkouts;
    case "TOTAL_SETS":
      return metrics.totalSets;
    case "LONGEST_WORKOUT_M":
      return metrics.longestWorkoutMeters;
    case "TOTAL_ACTIVE_MINUTES":
      return metrics.totalActiveMinutes;
    case "BEST_STREAK":
      return metrics.bestStreak;
    case "CURRENT_STREAK":
      return metrics.currentStreak;
    default:
      return 0;
  }
}

export async function evaluateAchievements(
  db: D1Database,
  userId: string,
  now: number,
): Promise<AchievementDto[]> {
  const definitions = await db
    .prepare(`SELECT * FROM achievements ORDER BY sort_order ASC, code ASC`)
    .all<AchievementRow>();
  const rows = definitions.results ?? [];
  if (rows.length === 0) return [];

  const unlocks = await db
    .prepare(`SELECT achievement_code, unlocked_at FROM achievement_unlocks WHERE user_id = ?1`)
    .bind(userId)
    .all<{ achievement_code: string; unlocked_at: number }>();
  const unlockedAt = new Map((unlocks.results ?? []).map((row) => [row.achievement_code, row.unlocked_at]));

  const metrics = await computeMetrics(db, userId);
  const out: AchievementDto[] = [];

  for (const row of rows) {
    const progress = progressFor(row.metric, metrics);
    let unlocked = unlockedAt.get(row.code) ?? null;

    if (unlocked === null && progress >= row.threshold) {
      const outcome: ClaimOutcome = await creditFixed(
        db,
        {
          userId,
          clientEventId: `achievement:${row.code}`,
          type: "ACHIEVEMENT",
          amount: row.reward_coins,
          reason: `Achievement unlocked — ${row.title}`,
          activityRef: row.code,
          now,
        },
        {
          sql: `INSERT INTO achievement_unlocks (user_id, achievement_code, progress, unlocked_at, transaction_id)
                VALUES (?1, ?2, ?3, ?4, NULL)`,
          bind: [userId, row.code, progress, now],
          conflictTable: "achievement_unlocks",
        },
      );
      unlocked = now;
      if (outcome.amount > 0 && !outcome.duplicate) {
        await db
          .prepare(
            `UPDATE achievement_unlocks SET transaction_id = ?1 WHERE user_id = ?2 AND achievement_code = ?3`,
          )
          .bind(outcome.transactionId, userId, row.code)
          .run();
      }
    }

    out.push({
      id: `ach.${row.code}`,
      code: row.code,
      title: row.title,
      description: row.description,
      icon: row.icon,
      category: row.category,
      threshold: row.threshold,
      progress,
      unlockedAt: unlocked,
    });
  }

  return out;
}
