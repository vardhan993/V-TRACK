import { ApiError } from "../lib/http";
import type { Ctx, Principal } from "../lib/router";
import { todayKey } from "../lib/time";

/** Largest JSON body any endpoint accepts, in bytes. */
const MAX_BODY_BYTES = 512 * 1024;

/** The authenticated caller. Every auth-required route starts with this. */
export function principalOf(ctx: Ctx): Principal {
  if (!ctx.principal) throw ApiError.unauthorized();
  return ctx.principal;
}

/** Reads and parses the JSON body, enforcing a size limit before parsing. */
export async function readJson<T = Record<string, unknown>>(ctx: Ctx): Promise<T> {
  const declared = Number(ctx.req.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
    throw ApiError.badRequest("payload_too_large", "Request body is too large.");
  }
  const text = await ctx.req.text();
  if (text.length > MAX_BODY_BYTES) {
    throw ApiError.badRequest("payload_too_large", "Request body is too large.");
  }
  if (text.trim().length === 0) return {} as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw ApiError.badRequest("invalid_json", "Request body is not valid JSON.");
  }
}

/**
 * The caller's "today", computed from the UTC offset stored on their account
 * (refreshed on every activity sync). All daily caps and challenge periods are
 * bucketed on this key, never on the server's clock alone.
 */
export function dayKeyOf(ctx: Ctx): string {
  return todayKey(principalOf(ctx).timezoneOffsetMinutes, ctx.now);
}

/** Reads an integer query parameter with bounds checking. */
export function intQuery(ctx: Ctx, name: string, fallback: number, min: number, max: number): number {
  const raw = ctx.query.get(name);
  if (raw === null || raw.trim() === "") return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) throw ApiError.badRequest("validation_error", `${name}: expected a whole number.`);
  return Math.max(min, Math.min(max, parsed));
}
