import { ApiError, jsonResponse } from "../lib/http";
import type { Router } from "../lib/router";
import { parseOrThrow } from "../lib/validate";
import { z } from "zod";
import {
  balanceOf,
  decodeCursor,
  encodeCursor,
  type TransactionRow,
} from "../services/coins";
import { dayStartMillis } from "../services/activity";
import { rulesFromEnv } from "../services/coinRules";
import { claimChallenge, listChallenges } from "../services/challenges";
import { evaluateAchievements } from "../services/achievements";
import { listRedemptions, listRewards, redeem } from "../services/rewards";
import { dayKeyOf, intQuery, principalOf, readJson } from "./helpers";

const noteSchema = z.object({ note: z.string().max(500, "Note is too long").nullable().optional() });

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

export function registerRewardRoutes(router: Router): void {
  router.get("/v1/coins/balance", async (ctx) => {
    const principal = principalOf(ctx);
    const rules = rulesFromEnv(ctx.env);
    const account = await balanceOf(ctx.db, principal.userId);
    const dayKey = dayKeyOf(ctx);
    const start = dayStartMillis(dayKey);

    const today = await ctx.db
      .prepare(
        `SELECT COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0) AS earned,
                COALESCE(SUM(CASE WHEN amount > 0 AND type = 'STEP_REWARD' THEN amount ELSE 0 END), 0) AS step_earned
         FROM coin_transactions
         WHERE user_id = ?1 AND created_at >= ?2 AND created_at < ?3`,
      )
      .bind(principal.userId, start, start + 86_400_000)
      .first<{ earned: number; step_earned: number }>();

    const earnedToday = today?.earned ?? 0;
    const stepEarnedToday = today?.step_earned ?? 0;

    return jsonResponse({
      confirmed: account.balance,
      pending: 0,
      earnedToday,
      dailyStepCap: rules.maxStepCoinsPerDay,
      remainingToday: Math.max(0, rules.maxStepCoinsPerDay - stepEarnedToday),
      lifetimeEarned: account.lifetimeEarned,
      engineVersion: rules.engineVersion,
    });
  });

  router.get("/v1/coins/transactions", async (ctx) => {
    const principal = principalOf(ctx);
    const limit = intQuery(ctx, "limit", 50, 1, 100);
    const cursorRaw = ctx.query.get("cursor");
    const cursor = cursorRaw ? decodeCursor(cursorRaw) : null;
    if (cursorRaw && !cursor) throw ApiError.badRequest("validation_error", "cursor is not valid.");

    const rows = await (async () => {
      if (cursor) {
        return ctx.db
          .prepare(
            `SELECT id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at
             FROM coin_transactions
             WHERE user_id = ?1 AND (created_at < ?2 OR (created_at = ?2 AND id < ?3))
             ORDER BY created_at DESC, id DESC LIMIT ?4`,
          )
          .bind(principal.userId, cursor.createdAt, cursor.id, limit + 1)
          .all<TransactionRow>();
      }
      return ctx.db
        .prepare(
          `SELECT id, user_id, client_event_id, type, amount, reason, activity_ref, status, balance_after, created_at
           FROM coin_transactions
           WHERE user_id = ?1
           ORDER BY created_at DESC, id DESC LIMIT ?2`,
        )
        .bind(principal.userId, limit + 1)
        .all<TransactionRow>();
    })();

    const items = (rows.results ?? []).slice(0, limit);
    const hasMore = (rows.results ?? []).length > limit;
    const last = items[items.length - 1];
    return jsonResponse({
      items: items.map(toTransactionDto),
      nextCursor: hasMore && last ? encodeCursor(last.created_at, last.id) : null,
    });
  });

  router.get("/v1/challenges", async (ctx) => {
    const principal = principalOf(ctx);
    return jsonResponse(await listChallenges(ctx.db, principal.userId, dayKeyOf(ctx)));
  });

  router.post("/v1/challenges/:id/claim", async (ctx) => {
    const principal = principalOf(ctx);
    const code = ctx.params.id ?? "";
    if (code.length === 0) throw ApiError.notFound("No such challenge.");

    const outcome = await claimChallenge(ctx.db, principal.userId, code, dayKeyOf(ctx), ctx.now);
    if (outcome.status === "NOT_FOUND") throw ApiError.notFound("That challenge is not available.");

    // "Not finished yet" is a normal state, not an error: the app shows the
    // remaining count straight from `reason`.
    return jsonResponse({
      transactionId: outcome.transactionId,
      clientEventId: outcome.clientEventId,
      amount: outcome.amount,
      status: outcome.status,
      balance: outcome.balance,
      reason: outcome.reason,
      duplicate: outcome.duplicate,
    });
  });

  router.get("/v1/achievements", async (ctx) => {
    const principal = principalOf(ctx);
    return jsonResponse(await evaluateAchievements(ctx.db, principal.userId, ctx.now));
  });

  router.get("/v1/rewards", async (ctx) => jsonResponse(await listRewards(ctx.db)));

  router.post("/v1/rewards/:id/redeem", async (ctx) => {
    const principal = principalOf(ctx);
    const input = parseOrThrow(noteSchema, await readJson(ctx));
    const rewardId = ctx.params.id ?? "";
    if (rewardId.length === 0) throw ApiError.notFound("No such reward.");

    const result = await redeem(ctx.db, principal.userId, rewardId, input.note ?? null, ctx.now);
    if (!result.ok) {
      if (result.reason === "not_found") throw ApiError.notFound("That reward is no longer available.");
      if (result.reason === "out_of_stock") throw ApiError.conflict("out_of_stock", "That reward is out of stock.");
      throw new ApiError(402, "insufficient_balance", "You do not have enough V-Coins for that reward.");
    }
    return jsonResponse(result.redemption);
  });

  router.get("/v1/redemptions", async (ctx) => {
    const principal = principalOf(ctx);
    return jsonResponse(await listRedemptions(ctx.db, principal.userId));
  });
}
