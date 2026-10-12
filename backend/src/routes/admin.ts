import { z } from "zod";
import { ApiError, jsonResponse } from "../lib/http";
import type { Router } from "../lib/router";
import { newId } from "../lib/ids";
import { parseOrThrow } from "../lib/validate";
import { requireAdmin } from "../services/auth";
import { balanceOf, creditFixed, isCheckViolation, type TxType, type TransactionRow } from "../services/coins";
import { findUserById, toUserDto } from "../services/users";
import { intQuery, readJson } from "./helpers";

const statusSchema = z.object({
  status: z.enum(["ACTIVE", "SUSPENDED"]),
});

const adjustSchema = z.object({
  userId: z.string().min(6, "Missing user id").max(64),
  amount: z.number().int().min(-100_000, "Amount is out of range").max(100_000, "Amount is out of range").refine((value) => value !== 0, "Amount cannot be zero"),
  reason: z.string().trim().min(4, "Give a reason").max(300, "Reason is too long"),
});

const usersQuerySchema = z.object({
  query: z.string().trim().max(120).optional(),
  status: z.enum(["ACTIVE", "SUSPENDED", "DELETED"]).optional(),
});

const redemptionPatchSchema = z.object({
  status: z.enum(["PENDING", "FULFILLED", "CANCELLED"]),
});

function toTransactionDto(row: TransactionRow) {
  return {
    id: row.id,
    clientEventId: row.client_event_id,
    type: row.type,
    amount: row.amount,
    reason: row.reason,
    activityRef: row.activity_ref,
    status: row.status,
    balanceAfter: row.balance_after,
    createdAt: row.created_at,
  };
}

export function registerAdminRoutes(router: Router): void {
  router.get("/v1/admin/stats", async (ctx) => {
    const principal = await requireAdmin(ctx.principal);
    const counts = await ctx.db
      .prepare(
        `SELECT
           (SELECT COUNT(*) FROM users) AS users,
           (SELECT COUNT(*) FROM users WHERE status = 'ACTIVE') AS active_users,
           (SELECT COUNT(*) FROM activity_days) AS activity_days,
           (SELECT COUNT(*) FROM workouts) AS workouts,
           (SELECT COALESCE(SUM(lifetime_earned), 0) FROM coin_accounts) AS coins_earned,
           (SELECT COALESCE(SUM(lifetime_spent), 0) FROM coin_accounts) AS coins_spent,
           (SELECT COUNT(*) FROM coin_transactions) AS transactions,
           (SELECT COUNT(*) FROM redemptions WHERE status = 'PENDING') AS pending_redemptions,
           (SELECT COUNT(*) FROM challenge_claims) AS challenge_claims,
           (SELECT COUNT(*) FROM achievement_unlocks) AS achievement_unlocks`,
      )
      .first();

    return jsonResponse({ ...counts, adminUserId: principal.userId, at: ctx.now });
  });

  router.get("/v1/admin/users", async (ctx) => {
    await requireAdmin(ctx.principal);
    const limit = intQuery(ctx, "limit", 50, 1, 200);
    const query = ctx.query.get("query")?.trim() ?? "";
    const status = ctx.query.get("status")?.trim() ?? "";
    const allowed = ["", "ACTIVE", "SUSPENDED", "DELETED"];
    if (!allowed.includes(status)) throw ApiError.badRequest("validation_error", "status must be ACTIVE, SUSPENDED or DELETED.");

    const rows = query
      ? await ctx.db
          .prepare(
            `SELECT id, email, display_name, status, is_admin, created_at, step_goal, fitness_level, primary_goal
             FROM users
             WHERE (?1 = '' OR email_normalized LIKE '%' || ?1 || '%' OR display_name LIKE '%' || ?1 || '%')
               AND (?2 = '' OR status = ?2)
             ORDER BY created_at DESC LIMIT ?3`,
          )
          .bind(query.toLowerCase(), status, limit)
          .all()
      : await ctx.db
          .prepare(
            `SELECT id, email, display_name, status, is_admin, created_at, step_goal, fitness_level, primary_goal
             FROM users
             WHERE (?1 = '' OR status = ?1)
             ORDER BY created_at DESC LIMIT ?2`,
          )
          .bind(status, limit)
          .all();

    return jsonResponse((rows.results ?? []).map((row: Record<string, unknown>) => ({
      id: row.id,
      email: row.email,
      displayName: row.display_name,
      status: row.status,
      isAdmin: row.is_admin === 1,
      createdAt: row.created_at,
      stepGoal: row.step_goal,
      fitnessLevel: row.fitness_level,
      primaryGoal: row.primary_goal,
    })));
  });

  router.get("/v1/admin/users/:id", async (ctx) => {
    await requireAdmin(ctx.principal);
    const user = await findUserById(ctx.db, ctx.params.id ?? "");
    if (!user) throw ApiError.notFound("No such user.");
    const account = await balanceOf(ctx.db, user.id);
    const recent = await ctx.db
      .prepare(
        `SELECT id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at
         FROM coin_transactions WHERE user_id = ?1 ORDER BY created_at DESC LIMIT 25`,
      )
      .bind(user.id)
      .all<TransactionRow>();

    return jsonResponse({
      user: toUserDto(user),
      balance: account,
      recentTransactions: (recent.results ?? []).map((row) => toTransactionDto(row)),
    });
  });

  router.post("/v1/admin/users/:id/status", async (ctx) => {
    const admin = await requireAdmin(ctx.principal);
    const input = parseOrThrow(statusSchema, await readJson(ctx));
    const userId = ctx.params.id ?? "";
    const user = await findUserById(ctx.db, userId);
    if (!user) throw ApiError.notFound("No such user.");

    // Bumping token_version on suspension invalidates every access token and
    // refresh token the account holds, in the same statement as the status change.
    await ctx.db
      .prepare(
        `UPDATE users SET status = ?1, token_version = token_version + 1, updated_at = ?2 WHERE id = ?3`,
      )
      .bind(input.status, ctx.now, userId)
      .run();

    await audit(ctx, admin.userId, "user.status", { userId, status: input.status });
    return jsonResponse({ id: userId, status: input.status });
  });

  router.post("/v1/admin/adjust", async (ctx) => {
    const admin = await requireAdmin(ctx.principal);
    const input = parseOrThrow(adjustSchema, await readJson(ctx));

    const user = await findUserById(ctx.db, input.userId);
    if (!user) throw ApiError.notFound("No such user.");

    if (input.amount > 0) {
      const outcome = await creditFixed(ctx.db, {
        userId: user.id,
        clientEventId: `admin:${newId()}`,
        type: "ADJUSTMENT" as TxType,
        amount: input.amount,
        reason: `Admin adjustment — ${input.reason}`,
        activityRef: admin.userId,
        now: ctx.now,
      });
      await audit(ctx, admin.userId, "coins.adjust", { userId: user.id, amount: input.amount, reason: input.reason, transactionId: outcome.transactionId });
      return jsonResponse({ ok: true, transactionId: outcome.transactionId, balance: outcome.balance });
    }

    const cost = -input.amount;
    const txId = newId();
    const balance = await balanceOf(ctx.db, user.id);
    if (balance.balance < cost) {
      throw ApiError.conflict("insufficient_balance", "That debit would take the balance below zero.");
    }

    try {
      await ctx.db.batch([
        // 1. The ledger row is the arbiter: it only exists if the balance was
        //    sufficient *before* the debit ran.
        ctx.db.prepare(
          `INSERT INTO coin_transactions
             (id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at)
           SELECT ?1, ?2, ?3, 'ADJUSTMENT', -?4, ?5, ?6, 'CONFIRMED', NULL, ?7
           WHERE (SELECT balance FROM coin_accounts WHERE user_id = ?2) >= ?4`,
        ).bind(txId, user.id, `admin:${newId()}`, cost, `Admin adjustment — ${input.reason}`, admin.userId, ctx.now),
        // 2. The debit only moves money when that ledger row exists, so the two
        //    can never disagree.
        ctx.db.prepare(
          `UPDATE coin_accounts SET balance = balance - ?1, lifetime_spent = lifetime_spent + ?1, updated_at = ?3
           WHERE user_id = ?2 AND balance >= ?1
             AND EXISTS (SELECT 1 FROM coin_transactions WHERE id = ?4 AND user_id = ?2)`,
        ).bind(cost, user.id, ctx.now, txId),
        ctx.db.prepare(
          `UPDATE coin_transactions SET balance_after = COALESCE((SELECT balance FROM coin_accounts WHERE user_id = ?2), 0)
           WHERE id = ?1 AND user_id = ?2`,
        ).bind(txId, user.id),
      ]);
    } catch (error) {
      if (isCheckViolation(error)) {
        throw ApiError.conflict("insufficient_balance", "That debit would take the balance below zero.");
      }
      throw error;
    }

    const written = await ctx.db
      .prepare(`SELECT id FROM coin_transactions WHERE id = ?1`)
      .bind(txId)
      .first<{ id: string }>();
    if (!written) throw ApiError.conflict("insufficient_balance", "That debit would take the balance below zero.");

    const after = await balanceOf(ctx.db, user.id);
    await audit(ctx, admin.userId, "coins.adjust", { userId: user.id, amount: input.amount, reason: input.reason, transactionId: txId });
    return jsonResponse({ ok: true, transactionId: txId, balance: after.balance });
  });

  router.get("/v1/admin/redemptions", async (ctx) => {
    await requireAdmin(ctx.principal);
    const limit = intQuery(ctx, "limit", 50, 1, 200);
    const status = ctx.query.get("status")?.trim() ?? "PENDING";
    const rows = await ctx.db
      .prepare(
        `SELECT r.id, r.user_id, r.reward_id, r.cost_coins, r.status, r.note, r.requested_at, r.resolved_at,
                w.title, u.email
         FROM redemptions r
         JOIN rewards w ON w.id = r.reward_id
         JOIN users u ON u.id = r.user_id
         WHERE (?1 = '' OR r.status = ?1)
         ORDER BY r.requested_at DESC LIMIT ?2`,
      )
      .bind(status, limit)
      .all();

    return jsonResponse((rows.results ?? []).map((row: Record<string, unknown>) => ({
      id: row.id,
      userId: row.user_id,
      userEmail: row.email,
      rewardId: row.reward_id,
      rewardTitle: row.title,
      costCoins: row.cost_coins,
      status: row.status,
      note: row.note,
      requestedAt: row.requested_at,
      resolvedAt: row.resolved_at,
    })));
  });

  router.patch("/v1/admin/redemptions/:id", async (ctx) => {
    const admin = await requireAdmin(ctx.principal);
    const input = parseOrThrow(redemptionPatchSchema, await readJson(ctx));
    const id = ctx.params.id ?? "";
    const row = await ctx.db
      .prepare(`SELECT id, user_id, status FROM redemptions WHERE id = ?1`)
      .bind(id)
      .first<{ id: string; user_id: string; status: string }>();
    if (!row) throw ApiError.notFound("No such redemption.");

    await ctx.db
      .prepare(
        `UPDATE redemptions SET status = ?1, resolved_at = ?2 WHERE id = ?3 AND status = 'PENDING'`,
      )
      .bind(input.status, ctx.now, id)
      .run();

    await audit(ctx, admin.userId, "redemption.status", { redemptionId: id, status: input.status });
    return jsonResponse({ id, status: input.status });
  });

  router.get("/v1/admin/audit", async (ctx) => {
    await requireAdmin(ctx.principal);
    const limit = intQuery(ctx, "limit", 50, 1, 200);
    const rows = await ctx.db
      .prepare(`SELECT id, admin_user_id, action, detail, created_at FROM admin_audit_log ORDER BY created_at DESC LIMIT ?1`)
      .bind(limit)
      .all();
    return jsonResponse(
      (rows.results ?? []).map((row: Record<string, unknown>) => ({
        id: row.id,
        adminUserId: row.admin_user_id,
        action: row.action,
        detail: row.detail,
        createdAt: row.created_at,
      })),
    );
  });
}

async function audit(
  ctx: { db: import("@cloudflare/workers-types").D1Database; now: number },
  adminUserId: string,
  action: string,
  detail: unknown,
): Promise<void> {
  await ctx.db
    .prepare(`INSERT INTO admin_audit_log (id, admin_user_id, action, detail, created_at) VALUES (?1, ?2, ?3, ?4, ?5)`)
    .bind(newId(), adminUserId, action, JSON.stringify(detail), ctx.now)
    .run();
}
