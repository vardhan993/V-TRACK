import type { D1Database } from "@cloudflare/workers-types";
import { newId } from "../lib/ids";

/**
 * The V-Coin ledger.
 *
 * Rules that make this server-authoritative rather than advisory:
 *
 *  1. The client never sends an amount. It sends "here are my steps today" or
 *     "here is the workout I finished"; the amount is derived here, from rows
 *     the server already stored during activity/workout sync.
 *  2. Idempotency is enforced by `UNIQUE (user_id, client_event_id)` in the
 *     database, not by application code, so a replay — even a concurrent one —
 *     can only ever produce a single ledger row.
 *  3. Every mutation of the balance, the ledger and the per-day caps happens in
 *     one `db.batch()`, which D1 executes as a single transaction. A failure
 *     anywhere rolls the whole thing back.
 *  4. `CHECK (balance >= 0)` on `coin_accounts` turns a double-spend into a
 *     constraint error instead of a negative balance, even if two requests race.
 *  5. Step and workout amounts are computed *inside* the SQL that writes them,
 *     against the live per-day row, so two concurrent claims cannot both credit
 *     the same increment.
 */

export type TxType = "STEP_REWARD" | "WORKOUT_REWARD" | "CHALLENGE_BONUS" | "ACHIEVEMENT" | "STREAK_BONUS" | "REDEMPTION" | "ADJUSTMENT";

export interface ClaimOutcome {
  transactionId: string;
  clientEventId: string;
  amount: number;
  status: string;
  balance: number;
  reason: string;
  duplicate: boolean;
}

export interface TransactionRow {
  id: string;
  user_id: string;
  client_event_id: string;
  type: string;
  amount: number;
  reason: string;
  activity_ref: string | null;
  status: string;
  balance_after: number | null;
  created_at: number;
}

export function isUniqueViolation(error: unknown, table?: string): boolean {
  const message = errorMessage(error);
  return message.includes("UNIQUE constraint failed") && (table === undefined || message.includes(table));
}

export function isCheckViolation(error: unknown): boolean {
  return errorMessage(error).includes("CHECK constraint failed");
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "object" && error !== null && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

/** Throws if D1 returned a failed result instead of raising, so nothing slips through. */
export function assertResults(results: Array<{ success: boolean; error?: string | null }>): void {
  for (const result of results) {
    if (!result.success) throw new Error(result.error ?? "D1 statement failed");
  }
}

export interface Balance {
  balance: number;
  lifetimeEarned: number;
  lifetimeSpent: number;
}

export async function ensureAccount(db: D1Database, userId: string, now: number): Promise<void> {
  await db
    .prepare(
      `INSERT OR IGNORE INTO coin_accounts (user_id, balance, lifetime_earned, lifetime_spent, updated_at)
       VALUES (?1, 0, 0, 0, ?2)`,
    )
    .bind(userId, now)
    .run();
}

export async function balanceOf(db: D1Database, userId: string): Promise<Balance> {
  const row = await db
    .prepare(
      `SELECT balance, lifetime_earned, lifetime_spent FROM coin_accounts WHERE user_id = ?1`,
    )
    .bind(userId)
    .first<{ balance: number; lifetime_earned: number; lifetime_spent: number }>();
  if (!row) return { balance: 0, lifetimeEarned: 0, lifetimeSpent: 0 };
  return { balance: row.balance, lifetimeEarned: row.lifetime_earned, lifetimeSpent: row.lifetime_spent };
}

export async function findTransaction(
  db: D1Database,
  userId: string,
  clientEventId: string,
): Promise<TransactionRow | null> {
  return db
    .prepare(
      `SELECT id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at
       FROM coin_transactions WHERE user_id = ?1 AND client_event_id = ?2`,
    )
    .bind(userId, clientEventId)
    .first<TransactionRow>();
}

async function outcomeFor(
  db: D1Database,
  row: TransactionRow,
  duplicate: boolean,
  reasonOverride?: string,
): Promise<ClaimOutcome> {
  const account = await balanceOf(db, row.user_id);
  return {
    transactionId: row.id,
    clientEventId: row.client_event_id,
    amount: row.amount,
    status: row.status,
    balance: account.balance,
    reason: reasonOverride ?? row.reason,
    duplicate,
  };
}

/** Outcome for a claim that legitimately earns nothing (no ledger row written). */
export async function noRewardOutcome(
  db: D1Database,
  userId: string,
  clientEventId: string,
  reason: string,
): Promise<ClaimOutcome> {
  const account = await balanceOf(db, userId);
  return {
    // Namespaced so the app can store it locally without colliding with a real id.
    transactionId: `noop:${clientEventId}`,
    clientEventId,
    amount: 0,
    status: "NO_REWARD",
    balance: account.balance,
    reason,
    duplicate: false,
  };
}

/* --------------------------------------------------------------- step claim */

export interface StepClaimInput {
  userId: string;
  clientEventId: string;
  dayKey: string;
  /** Cumulative steps the client reports for the day. */
  steps: number;
  activeSeconds: number;
  now: number;
  minClaimGapSeconds: number;
  maxCreditedStepsPerDay: number;
  maxStepCoinsPerDay: number;
  coinsPerStep: number;
  minStepsForReward: number;
}

/**
 * Credits the *increment* between what the day has already awarded and what the
 * day is worth now. Re-sending the same cumulative total therefore earns 0, and
 * the SQL guard (`MIN(target, cap) > already_awarded`) makes that true even for
 * two claims racing each other.
 */
export async function claimSteps(db: D1Database, input: StepClaimInput): Promise<ClaimOutcome> {
  const existing = await findTransaction(db, input.userId, input.clientEventId);
  if (existing) return outcomeFor(db, existing, true);

  const verified = await db
    .prepare(`SELECT steps, verified_steps FROM activity_days WHERE user_id = ?1 AND day_key = ?2`)
    .bind(input.userId, input.dayKey)
    .first<{ steps: number; verified_steps: number }>();

  if (!verified) {
    return noRewardOutcome(db, input.userId, input.clientEventId,
      "No synced activity for that day yet — open the app online to sync your steps.");
  }
  if (verified.verified_steps < input.minStepsForReward) {
    return noRewardOutcome(db, input.userId, input.clientEventId,
      `Rewards start at ${input.minStepsForReward.toLocaleString("en-US")} verified steps in a day.`);
  }

  const credited = Math.min(input.steps, verified.verified_steps, input.maxCreditedStepsPerDay);
  const target = Math.min(
    Math.floor(credited * input.coinsPerStep),
    input.maxStepCoinsPerDay,
  );
  if (target <= 0) {
    return noRewardOutcome(db, input.userId, input.clientEventId, "No reward-eligible steps in that day.");
  }

  const txId = newId();
  const gapMs = Math.max(0, input.minClaimGapSeconds) * 1000;
  const reason = `Daily steps reward — ${input.dayKey}`;

  try {
    const results = await db.batch([
      db.prepare(
        `INSERT OR IGNORE INTO daily_coin_earnings
           (user_id, day_key, step_coins_awarded, workout_rewards_awarded, last_step_claim_at, created_at, updated_at)
         VALUES (?1, ?2, 0, 0, NULL, ?3, ?3)`,
      ).bind(input.userId, input.dayKey, input.now),

      db.prepare(
        `INSERT INTO coin_transactions
           (id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at)
         SELECT ?1, ?2, ?3, 'STEP_REWARD',
                MAX(0, MIN(?4, ?5) - COALESCE(d.step_coins_awarded, 0)),
                ?6, ?7, 'CONFIRMED', NULL, ?8
         FROM daily_coin_earnings d
         WHERE d.user_id = ?2 AND d.day_key = ?7
           AND MIN(?4, ?5) > COALESCE(d.step_coins_awarded, 0)
           AND (d.last_step_claim_at IS NULL OR (?8 - d.last_step_claim_at) >= ?9)
         RETURNING id, amount`,
      ).bind(txId, input.userId, input.clientEventId, target, input.maxStepCoinsPerDay, reason, input.dayKey, input.now, gapMs),

      db.prepare(
        `UPDATE daily_coin_earnings SET
           step_coins_awarded = step_coins_awarded
             + COALESCE((SELECT amount FROM coin_transactions WHERE id = ?1 AND user_id = ?2), 0),
           last_step_claim_at = CASE
             WHEN (SELECT COUNT(*) FROM coin_transactions WHERE id = ?1 AND user_id = ?2) > 0 THEN ?3
             ELSE last_step_claim_at END,
           updated_at = ?3
         WHERE user_id = ?2 AND day_key = ?4`,
      ).bind(txId, input.userId, input.now, input.dayKey),

      db.prepare(
        `UPDATE coin_accounts SET
           balance = balance + COALESCE((SELECT amount FROM coin_transactions WHERE id = ?1 AND user_id = ?2), 0),
           lifetime_earned = lifetime_earned + COALESCE((SELECT amount FROM coin_transactions WHERE id = ?1 AND user_id = ?2), 0),
           updated_at = ?3
         WHERE user_id = ?2`,
      ).bind(txId, input.userId, input.now),

      db.prepare(
        `UPDATE coin_transactions SET
           balance_after = COALESCE((SELECT balance FROM coin_accounts WHERE user_id = ?2), 0)
         WHERE id = ?1 AND user_id = ?2`,
      ).bind(txId, input.userId),
    ]);
    assertResults(results);
  } catch (error) {
    if (isUniqueViolation(error, "coin_transactions")) {
      const raced = await findTransaction(db, input.userId, input.clientEventId);
      if (raced) return outcomeFor(db, raced, true);
    }
    throw error;
  }

  const created = await findTransaction(db, input.userId, input.clientEventId);
  if (!created) {
    const balance = await balanceOf(db, input.userId);
    const claimed = await db
      .prepare(`SELECT last_step_claim_at FROM daily_coin_earnings WHERE user_id = ?1 AND day_key = ?2`)
      .bind(input.userId, input.dayKey)
      .first<{ last_step_claim_at: number | null }>();
    const last = claimed?.last_step_claim_at ?? null;
    const reasonText =
      last !== null && input.now - last < gapMs
        ? `Steps are credited at most every ${Math.round(gapMs / 60000)} minutes.`
        : "No new reward-eligible steps since your last claim.";
    return {
      transactionId: `noop:${input.clientEventId}`,
      clientEventId: input.clientEventId,
      amount: 0,
      status: "NO_REWARD",
      balance: balance.balance,
      reason: reasonText,
      duplicate: false,
    };
  }
  return outcomeFor(db, created, false);
}

/* ----------------------------------------------------------- workout claim */

export interface WorkoutClaimInput {
  userId: string;
  clientEventId: string;
  workoutClientId: string;
  dayKey: string;
  now: number;
  amount: number;
  reason: string;
  maxWorkoutRewardsPerDay: number;
}

export async function claimWorkout(db: D1Database, input: WorkoutClaimInput): Promise<ClaimOutcome> {
  const existing = await findTransaction(db, input.userId, input.clientEventId);
  if (existing) return outcomeFor(db, existing, true);

  if (input.amount <= 0) {
    return noRewardOutcome(db, input.userId, input.clientEventId, input.reason);
  }

  const txId = newId();
  try {
    const results = await db.batch([
      db.prepare(
        `INSERT OR IGNORE INTO daily_coin_earnings
           (user_id, day_key, step_coins_awarded, workout_rewards_awarded, last_step_claim_at, created_at, updated_at)
         VALUES (?1, ?2, 0, 0, NULL, ?3, ?3)`,
      ).bind(input.userId, input.dayKey, input.now),

      db.prepare(
        `INSERT INTO coin_transactions
           (id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at)
         SELECT ?1, ?2, ?3, 'WORKOUT_REWARD', ?4, ?5, ?6, 'CONFIRMED', NULL, ?7
         FROM workouts w
         LEFT JOIN daily_coin_earnings d ON d.user_id = w.user_id AND d.day_key = ?8
         WHERE w.user_id = ?2 AND w.client_id = ?6
           AND ?4 > 0
           AND COALESCE(d.workout_rewards_awarded, 0) < ?9
         RETURNING id, amount`,
      ).bind(txId, input.userId, input.clientEventId, input.amount, input.reason, input.workoutClientId, input.now, input.dayKey, input.maxWorkoutRewardsPerDay),

      db.prepare(
        `UPDATE daily_coin_earnings SET
           workout_rewards_awarded = workout_rewards_awarded
             + (SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END FROM coin_transactions WHERE id = ?1 AND user_id = ?2),
           updated_at = ?3
         WHERE user_id = ?2 AND day_key = ?4`,
      ).bind(txId, input.userId, input.now, input.dayKey),

      db.prepare(
        `UPDATE coin_accounts SET
           balance = balance + COALESCE((SELECT amount FROM coin_transactions WHERE id = ?1 AND user_id = ?2), 0),
           lifetime_earned = lifetime_earned + COALESCE((SELECT amount FROM coin_transactions WHERE id = ?1 AND user_id = ?2), 0),
           updated_at = ?3
         WHERE user_id = ?2`,
      ).bind(txId, input.userId, input.now),

      db.prepare(
        `UPDATE coin_transactions SET
           balance_after = COALESCE((SELECT balance FROM coin_accounts WHERE user_id = ?2), 0)
         WHERE id = ?1 AND user_id = ?2`,
      ).bind(txId, input.userId),
    ]);
    assertResults(results);
  } catch (error) {
    if (isUniqueViolation(error, "coin_transactions")) {
      const raced = await findTransaction(db, input.userId, input.clientEventId);
      if (raced) return outcomeFor(db, raced, true);
    }
    throw error;
  }

  const created = await findTransaction(db, input.userId, input.clientEventId);
  if (!created) {
    const balance = await balanceOf(db, input.userId);
    return {
      transactionId: `noop:${input.clientEventId}`,
      clientEventId: input.clientEventId,
      amount: 0,
      status: "NO_REWARD",
      balance: balance.balance,
      reason: `${input.reason} (daily workout reward limit reached, or the workout is not synced yet.)`,
      duplicate: false,
    };
  }
  return outcomeFor(db, created, false);
}

/* ------------------------------------------------------------ fixed credits */

export interface FixedCreditInput {
  userId: string;
  clientEventId: string;
  type: TxType;
  amount: number;
  reason: string;
  activityRef: string | null;
  now: number;
}

/**
 * Flat bonus (challenge, achievement, streak). Idempotent on `client_event_id`.
 * When [beforeInsert] is supplied it runs as the first statement of the same
 * transaction and is used as an additional claim guard (its unique index is the
 * arbiter for "already claimed this period").
 */
export async function creditFixed(
  db: D1Database,
  input: FixedCreditInput,
  beforeInsert?: { sql: string; bind: unknown[]; conflictTable: string },
): Promise<ClaimOutcome> {
  const existing = await findTransaction(db, input.userId, input.clientEventId);
  if (existing) return outcomeFor(db, existing, true);

  if (input.amount <= 0) {
    // Still run the guard so an "unlocked but free" milestone is recorded once.
    if (beforeInsert) {
      try {
        await db.prepare(beforeInsert.sql).bind(...beforeInsert.bind).run();
      } catch (error) {
        if (!isUniqueViolation(error, beforeInsert.conflictTable)) throw error;
        return noRewardOutcome(db, input.userId, input.clientEventId, input.reason);
      }
    }
    return noRewardOutcome(db, input.userId, input.clientEventId, input.reason);
  }

  const txId = newId();
  const statements = [];
  if (beforeInsert) {
    statements.push(db.prepare(beforeInsert.sql).bind(...beforeInsert.bind));
  }
  statements.push(
    db.prepare(
      `INSERT INTO coin_transactions
         (id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'CONFIRMED', NULL, ?8)`,
    ).bind(txId, input.userId, input.clientEventId, input.type, input.amount, input.reason, input.activityRef, input.now),
    db.prepare(
      `UPDATE coin_accounts SET
         balance = balance + ?1,
         lifetime_earned = lifetime_earned + ?1,
         updated_at = ?3
       WHERE user_id = ?2`,
    ).bind(input.amount, input.userId, input.now),
    db.prepare(
      `UPDATE coin_transactions SET
         balance_after = COALESCE((SELECT balance FROM coin_accounts WHERE user_id = ?2), 0)
       WHERE id = ?1 AND user_id = ?2`,
    ).bind(txId, input.userId),
  );

  try {
    assertResults(await db.batch(statements));
  } catch (error) {
    if (isUniqueViolation(error)) {
      const raced = await findTransaction(db, input.userId, input.clientEventId);
      if (raced) return outcomeFor(db, raced, true);
      // The guard table rejected the claim: someone else already claimed it.
      const balance = await balanceOf(db, input.userId);
      return {
        transactionId: `noop:${input.clientEventId}`,
        clientEventId: input.clientEventId,
        amount: 0,
        status: "DUPLICATE",
        balance: balance.balance,
        reason: "Already claimed.",
        duplicate: true,
      };
    }
    throw error;
  }

  const created = await findTransaction(db, input.userId, input.clientEventId);
  if (!created) return noRewardOutcome(db, input.userId, input.clientEventId, input.reason);
  return outcomeFor(db, created, false);
}

/* ---------------------------------------------------------------- spending */

export interface DebitInput {
  userId: string;
  clientEventId: string;
  redemptionId: string;
  rewardId: string;
  rewardTitle: string;
  costCoins: number;
  note: string | null;
  now: number;
}

/**
 * Debits a redemption. The `CHECK (balance >= 0)` constraint is what actually
 * prevents an overdraft: if the debit would drive the balance negative the
 * statement fails and D1 rolls back the entire batch, so the redemption row can
 * never be written without its payment.
 */
export async function debitForRedemption(
  db: D1Database,
  input: DebitInput,
): Promise<{ ok: true; outcome: ClaimOutcome } | { ok: false; reason: "insufficient" | "duplicate" | "inactive" }> {
  const existing = await findTransaction(db, input.userId, input.clientEventId);
  if (existing) return { ok: true, outcome: await outcomeFor(db, existing, true) };

  const account = await balanceOf(db, input.userId);
  if (account.balance < input.costCoins) return { ok: false, reason: "insufficient" };

  const txId = newId();
  try {
    assertResults(
      await db.batch([
        db.prepare(
          `INSERT INTO redemptions (id, user_id, reward_id, cost_coins, status, note, requested_at, resolved_at)
           SELECT ?1, ?2, ?3, ?4, 'PENDING', ?5, ?6, NULL
           FROM rewards r
           WHERE r.id = ?3 AND r.is_active = 1
             AND (r.stock IS NULL OR r.stock > 0)
             AND (SELECT balance FROM coin_accounts WHERE user_id = ?2) >= ?4`,
        ).bind(input.redemptionId, input.userId, input.rewardId, input.costCoins, input.note, input.now),

        db.prepare(
          `UPDATE coin_accounts SET
             balance = balance - ?1,
             lifetime_spent = lifetime_spent + ?1,
             updated_at = ?3
           WHERE user_id = ?2
             AND balance >= ?1
             AND EXISTS (SELECT 1 FROM redemptions WHERE id = ?4 AND user_id = ?2)`,
        ).bind(input.costCoins, input.userId, input.now, input.redemptionId),

        db.prepare(
          `UPDATE rewards SET stock = stock - 1
           WHERE id = ?1 AND stock IS NOT NULL
             AND EXISTS (SELECT 1 FROM redemptions WHERE id = ?2)`,
        ).bind(input.rewardId, input.redemptionId),

        db.prepare(
          `INSERT INTO coin_transactions
             (id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at)
           SELECT ?1, ?2, ?3, 'REDEMPTION', -?4, ?5, ?6, 'CONFIRMED', NULL, ?7
           WHERE EXISTS (SELECT 1 FROM redemptions WHERE id = ?8 AND user_id = ?2)`,
        ).bind(txId, input.userId, input.clientEventId, input.costCoins, `Redeemed: ${input.rewardTitle}`, input.rewardId, input.now, input.redemptionId),

        db.prepare(
          `UPDATE coin_transactions SET
             balance_after = COALESCE((SELECT balance FROM coin_accounts WHERE user_id = ?2), 0)
           WHERE id = ?1 AND user_id = ?2`,
        ).bind(txId, input.userId),
      ]),
    );
  } catch (error) {
    if (isCheckViolation(error) || isUniqueViolation(error, "coin_transactions")) {
      return { ok: false, reason: "insufficient" };
    }
    throw error;
  }

  const redemption = await db
    .prepare(`SELECT id FROM redemptions WHERE id = ?1`)
    .bind(input.redemptionId)
    .first<{ id: string }>();
  if (!redemption) return { ok: false, reason: "inactive" };

  const created = await findTransaction(db, input.userId, input.clientEventId);
  const balance = await balanceOf(db, input.userId);
  return {
    ok: true,
    outcome: {
      transactionId: created?.id ?? txId,
      clientEventId: input.clientEventId,
      amount: created?.amount ?? -input.costCoins,
      status: "CONFIRMED",
      balance: balance.balance,
      reason: created?.reason ?? `Redeemed: ${input.rewardTitle}`,
      duplicate: created === null,
    },
  };
}

/* ------------------------------------------------------------------ paging */

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export function encodeCursor(createdAt: number, id: string): string {
  return btoa(`${createdAt}:${id}`).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function decodeCursor(cursor: string): { createdAt: number; id: string } | null {
  try {
    const padded = cursor.replace(/-/g, "+").replace(/_/g, "/");
    const raw = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
    const separator = raw.lastIndexOf(":");
    if (separator <= 0) return null;
    const createdAt = Number.parseInt(raw.slice(0, separator), 10);
    const id = raw.slice(separator + 1);
    if (!Number.isFinite(createdAt) || id.length === 0) return null;
    return { createdAt, id };
  } catch {
    return null;
  }
}
