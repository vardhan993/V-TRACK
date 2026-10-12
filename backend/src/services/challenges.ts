import type { D1Database } from "@cloudflare/workers-types";
import { newId } from "../lib/ids";
import { addDays, dayRange, weekStartKey } from "../lib/time";
import { metricsForRange } from "./activity";
import { creditFixed, type ClaimOutcome } from "./coins";

/**
 * Daily / weekly challenges.
 *
 * Progress is always computed from data the server already holds (activity days
 * and workouts), so a client cannot post a fake "I did it" and collect the
 * bonus. Claiming is idempotent per (user, challenge, period) through the
 * `challenge_claims` primary key.
 */

export interface ChallengeRow {
  code: string;
  title: string;
  description: string;
  type: string;
  period: "DAILY" | "WEEKLY";
  target_value: number;
  reward_coins: number;
  is_active: number;
  sort_order: number;
}

export interface ChallengeDto {
  id: string;
  code: string;
  title: string;
  description: string;
  type: string;
  period: string;
  targetValue: number;
  rewardCoins: number;
  isActive: boolean;
  currentValue: number;
  completed: boolean;
  claimed: boolean;
  periodKey: string;
}

export interface ChallengeListDto {
  items: ChallengeDto[];
  periodKey: string;
}

export function periodKeyFor(period: "DAILY" | "WEEKLY", dayKey: string): string {
  return period === "DAILY" ? dayKey : weekStartKey(dayKey);
}

function periodBounds(period: "DAILY" | "WEEKLY", dayKey: string): { from: string; to: string } {
  if (period === "DAILY") return { from: dayKey, to: dayKey };
  const start = weekStartKey(dayKey);
  return { from: start, to: addDays(start, 6) };
}

async function currentValueFor(
  db: D1Database,
  userId: string,
  challenge: ChallengeRow,
  dayKey: string,
): Promise<number> {
  const { from, to } = periodBounds(challenge.period, dayKey);
  const metrics = await metricsForRange(db, userId, from, to);

  switch (challenge.type) {
    case "STEPS":
      return metrics.steps;
    case "ACTIVE_MINUTES":
      return metrics.activeMinutes;
    case "WORKOUT_MINUTES":
      return metrics.workoutMinutes;
    case "WORKOUT_COUNT":
      return metrics.workoutCount;
    default:
      return 0;
  }
}

export async function listChallenges(
  db: D1Database,
  userId: string,
  dayKey: string,
): Promise<ChallengeListDto> {
  const rows = await db
    .prepare(`SELECT * FROM challenges WHERE is_active = 1 ORDER BY sort_order ASC, code ASC`)
    .all<ChallengeRow>();
  const challenges = rows.results ?? [];
  if (challenges.length === 0) return { items: [], periodKey: dayKey };

  const periodKeys = [...new Set(challenges.map((challenge) => periodKeyFor(challenge.period, dayKey)))];
  const claims = await db
    .prepare(
      `SELECT challenge_code, period_key FROM challenge_claims
       WHERE user_id = ?1 AND period_key IN (${periodKeys.map((_, i) => `?${i + 2}`).join(", ")})`,
    )
    .bind(userId, ...periodKeys)
    .all<{ challenge_code: string; period_key: string }>();

  const claimed = new Set((claims.results ?? []).map((row) => `${row.challenge_code}|${row.period_key}`));

  const items: ChallengeDto[] = [];
  for (const challenge of challenges) {
    const key = periodKeyFor(challenge.period, dayKey);
    const value = await currentValueFor(db, userId, challenge, dayKey);
    items.push({
      id: challenge.code,
      code: challenge.code,
      title: challenge.title,
      description: challenge.description,
      type: challenge.type,
      period: challenge.period,
      targetValue: challenge.target_value,
      rewardCoins: challenge.reward_coins,
      isActive: challenge.is_active === 1,
      currentValue: value,
      completed: value >= challenge.target_value,
      claimed: claimed.has(`${challenge.code}|${key}`),
      periodKey: key,
    });
  }

  return { items, periodKey: dayKey };
}

export async function claimChallenge(
  db: D1Database,
  userId: string,
  challengeCode: string,
  dayKey: string,
  now: number,
): Promise<ClaimOutcome> {
  const challenge = await db
    .prepare(`SELECT * FROM challenges WHERE code = ?1 AND is_active = 1`)
    .bind(challengeCode)
    .first<ChallengeRow>();

  if (!challenge) {
    return {
      transactionId: `noop:${challengeCode}`,
      clientEventId: challengeCode,
      amount: 0,
      status: "NOT_FOUND",
      balance: 0,
      reason: "That challenge is not available.",
      duplicate: false,
    };
  }

  const key = periodKeyFor(challenge.period, dayKey);
  const value = await currentValueFor(db, userId, challenge, dayKey);
  const clientEventId = `challenge:${challenge.code}:${key}`;

  if (value < challenge.target_value) {
    const remaining = Math.max(0, Math.ceil(challenge.target_value - value));
    return {
      transactionId: `noop:${clientEventId}`,
      clientEventId,
      amount: 0,
      status: "IN_PROGRESS",
      balance: (await balanceFor(db, userId)).balance,
      reason: `${challenge.title}: ${remaining.toLocaleString("en-US")} to go.`,
      duplicate: false,
    };
  }

  // The challenge_claims primary key is the claim guard: it is the FIRST
  // statement of the transaction, so a second claim for the same period rolls
  // the whole batch back before a single coin moves.
  const outcome = await creditFixed(
    db,
    {
      userId,
      clientEventId,
      type: "CHALLENGE_BONUS",
      amount: challenge.reward_coins,
      reason: `Challenge complete — ${challenge.title}`,
      activityRef: challenge.code,
      now,
    },
    {
      sql: `INSERT INTO challenge_claims (user_id, challenge_code, period_key, claimed_at, transaction_id)
            VALUES (?1, ?2, ?3, ?4, NULL)`,
      bind: [userId, challenge.code, key, now],
      conflictTable: "challenge_claims",
    },
  );

  if (outcome.amount > 0 && !outcome.duplicate) {
    await db
      .prepare(`UPDATE challenge_claims SET transaction_id = ?1 WHERE user_id = ?2 AND challenge_code = ?3 AND period_key = ?4`)
      .bind(outcome.transactionId, userId, challenge.code, key)
      .run();
  }

  return outcome;
}

async function balanceFor(db: D1Database, userId: string): Promise<{ balance: number }> {
  const row = await db
    .prepare(`SELECT balance FROM coin_accounts WHERE user_id = ?1`)
    .bind(userId)
    .first<{ balance: number }>();
  return { balance: row?.balance ?? 0 };
}

/** Exposed for tests: the exact day list a period covers. */
export function periodDays(period: "DAILY" | "WEEKLY", dayKey: string): string[] {
  const { from, to } = periodBounds(period, dayKey);
  return dayRange(from, to, 7);
}
