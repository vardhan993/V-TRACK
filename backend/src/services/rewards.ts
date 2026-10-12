import type { D1Database } from "@cloudflare/workers-types";
import { newId } from "../lib/ids";
import { debitForRedemption, type ClaimOutcome } from "./coins";

/**
 * Reward catalogue and redemptions.
 *
 * V-Coins are virtual credits: the catalogue is fulfilled by the operator as
 * in-app perks, there is no cash-out path and nothing here implies monetary
 * value. Spending is a server-side debit guarded by `CHECK (balance >= 0)`.
 */

export interface RewardRow {
  id: string;
  title: string;
  description: string;
  category: string;
  cost_coins: number;
  emoji: string | null;
  stock: number | null;
  fulfillment_note: string | null;
  is_active: number;
  sort_order: number;
}

export interface RewardDto {
  id: string;
  title: string;
  description: string;
  category: string;
  costCoins: number;
  emoji: string | null;
  stock: number | null;
  fulfillmentNote: string | null;
  isActive: boolean;
}

export interface RedemptionRow {
  id: string;
  user_id: string;
  reward_id: string;
  cost_coins: number;
  status: string;
  note: string | null;
  requested_at: number;
  resolved_at: number | null;
}

export interface RedemptionDto {
  id: string;
  rewardId: string;
  rewardTitle: string;
  costCoins: number;
  status: string;
  note: string | null;
  requestedAt: number;
}

export function toRewardDto(row: RewardRow): RewardDto {
  return {
    id: row.id,
    title: row.title,
    description: row.description,
    category: row.category,
    costCoins: row.cost_coins,
    emoji: row.emoji,
    stock: row.stock,
    fulfillmentNote: row.fulfillment_note,
    isActive: row.is_active === 1,
  };
}

export async function listRewards(db: D1Database): Promise<RewardDto[]> {
  const rows = await db
    .prepare(`SELECT * FROM rewards WHERE is_active = 1 ORDER BY sort_order ASC, title ASC`)
    .all<RewardRow>();
  return (rows.results ?? []).map(toRewardDto);
}

export type RedeemResult =
  | { ok: true; redemption: RedemptionDto; outcome: ClaimOutcome }
  | { ok: false; reason: "not_found" | "insufficient" | "out_of_stock" };

export async function redeem(
  db: D1Database,
  userId: string,
  rewardId: string,
  note: string | null,
  now: number,
): Promise<RedeemResult> {
  const reward = await db
    .prepare(`SELECT * FROM rewards WHERE id = ?1 AND is_active = 1`)
    .bind(rewardId)
    .first<RewardRow>();
  if (!reward) return { ok: false, reason: "not_found" };
  if (reward.stock !== null && reward.stock <= 0) return { ok: false, reason: "out_of_stock" };

  const clientEventId = `redeem:${rewardId}:${newId()}`;
  const redemptionId = newId();

  const result = await debitForRedemption(db, {
    userId,
    clientEventId,
    redemptionId,
    rewardId,
    rewardTitle: reward.title,
    costCoins: reward.cost_coins,
    note,
    now,
  });

  if (!result.ok) return { ok: false, reason: result.reason === "insufficient" ? "insufficient" : "out_of_stock" };

  const row = await db
    .prepare(
      `SELECT r.id, r.user_id, r.reward_id, r.cost_coins, r.status, r.note, r.requested_at, w.title
       FROM redemptions r JOIN rewards w ON w.id = r.reward_id
       WHERE r.id = ?1`,
    )
    .bind(redemptionId)
    .first<RedemptionRow & { title: string }>();

  return {
    ok: true,
    redemption: {
      id: redemptionId,
      rewardId,
      rewardTitle: row?.title ?? reward.title,
      costCoins: row?.cost_coins ?? reward.cost_coins,
      status: row?.status ?? "PENDING",
      note: row?.note ?? note,
      requestedAt: row?.requested_at ?? now,
    },
    outcome: result.outcome,
  };
}

export async function listRedemptions(db: D1Database, userId: string): Promise<RedemptionDto[]> {
  const rows = await db
    .prepare(
      `SELECT r.id, r.user_id, r.reward_id, r.cost_coins, r.status, r.note, r.requested_at, w.title
       FROM redemptions r JOIN rewards w ON w.id = r.reward_id
       WHERE r.user_id = ?1
       ORDER BY r.requested_at DESC LIMIT 100`,
    )
    .bind(userId)
    .all<RedemptionRow & { title: string }>();

  return (rows.results ?? []).map((row) => ({
    id: row.id,
    rewardId: row.reward_id,
    rewardTitle: row.title,
    costCoins: row.cost_coins,
    status: row.status,
    note: row.note,
    requestedAt: row.requested_at,
  }));
}
