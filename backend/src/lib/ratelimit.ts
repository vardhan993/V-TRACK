import type { D1Database } from "@cloudflare/workers-types";
import { ApiError } from "./http";

/**
 * Two layers of abuse control, chosen to stay inside the D1 Free write budget
 * (100,000 rows written per day):
 *
 *  1. a free in-memory sliding window applied to *every* request — zero database
 *     cost, and good enough to blunt a burst that lands on one isolate;
 *  2. a D1-backed fixed window applied only to credential endpoints, where the
 *     write volume is naturally low and the consequence of abuse is high.
 *
 * Both fail closed for authentication: when the limiter itself errors, the
 * request is refused rather than allowed through.
 */

const memoryHits = new Map<string, { windowStart: number; count: number }>();
const MEMORY_WINDOW_MS = 60_000;

/** Best-effort per-isolate limiter. Returns seconds to wait, or null to allow. */
export function memoryRateLimit(key: string, limit: number, now: number): number | null {
  const windowStart = Math.floor(now / MEMORY_WINDOW_MS) * MEMORY_WINDOW_MS;
  const entry = memoryHits.get(key);
  if (!entry || entry.windowStart !== windowStart) {
    if (memoryHits.size > 5_000) memoryHits.clear();
    memoryHits.set(key, { windowStart, count: 1 });
    return null;
  }
  entry.count += 1;
  if (entry.count <= limit) return null;
  return Math.max(1, Math.ceil((windowStart + MEMORY_WINDOW_MS - now) / 1000));
}

export interface FixedWindowResult {
  allowed: boolean;
  retryAfterSeconds: number;
  remaining: number;
}

/**
 * Fixed-window counter persisted in D1. The whole read-modify-write happens in
 * one statement (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`) so two
 * concurrent requests cannot both believe they are the first.
 */
export async function fixedWindowRateLimit(
  db: D1Database,
  bucketKey: string,
  limit: number,
  windowSeconds: number,
  now: number,
): Promise<FixedWindowResult> {
  const windowStart = Math.floor(now / (windowSeconds * 1000)) * windowSeconds * 1000;

  const row = await db
    .prepare(
      `INSERT INTO rate_limits (bucket_key, window_start, count, updated_at)
       VALUES (?1, ?2, 1, ?3)
       ON CONFLICT(bucket_key) DO UPDATE SET
         count = CASE WHEN rate_limits.window_start = ?2 THEN rate_limits.count + 1 ELSE 1 END,
         updated_at = ?3
       RETURNING count, window_start`,
    )
    .bind(bucketKey, windowStart, now)
    .first<{ count: number; window_start: number }>();

  const count = row?.count ?? 1;
  if (count > limit) {
    const resetAt = (row?.window_start ?? windowStart) + windowSeconds * 1000;
    return {
      allowed: false,
      retryAfterSeconds: Math.max(1, Math.ceil((resetAt - now) / 1000)),
      remaining: 0,
    };
  }
  return { allowed: true, retryAfterSeconds: 0, remaining: Math.max(0, limit - count) };
}

/** Enforces a fixed window or throws the 429 the Android client understands. */
export async function enforceRateLimit(
  db: D1Database,
  bucketKey: string,
  limit: number,
  windowSeconds: number,
  now: number,
  message = "Too many requests. Please wait a moment and try again.",
): Promise<void> {
  const memoryWait = memoryRateLimit(bucketKey, limit, now);
  if (memoryWait !== null) throw ApiError.tooMany(message, memoryWait);

  let result: FixedWindowResult;
  try {
    result = await fixedWindowRateLimit(db, bucketKey, limit, windowSeconds, now);
  } catch (error) {
    console.error("rate limit unavailable", error);
    throw ApiError.tooMany("Rate limiting is unavailable right now. Try again shortly.", windowSeconds);
  }
  if (!result.allowed) throw ApiError.tooMany(message, result.retryAfterSeconds);
}
