/**
 * Bindings and configuration for the V-Track Worker.
 *
 * Everything listed as `?` is optional at runtime and falls back to a documented
 * default, so a fresh `wrangler dev` works with no secrets in place. The three
 * that MUST be set for a real deployment are supplied with `wrangler secret put`:
 *
 *   JWT_SECRET       signs short-lived access tokens (32+ random bytes)
 *   PASSWORD_PEPPER  mixed into every password hash (32+ random bytes)
 *   ADMIN_EMAILS     comma-separated addresses allowed to use /v1/admin/*
 *
 * No secret is ever read from, or written to, application source code.
 */
export interface Env {
  /** Cloudflare D1 binding (see wrangler.jsonc). */
  DB: D1Database;

  ENVIRONMENT?: string;

  /** Optional but strongly recommended in production. */
  JWT_SECRET?: string;
  PASSWORD_PEPPER?: string;
  ADMIN_EMAILS?: string;

  COIN_ENGINE_VERSION?: string;
  COINS_PER_STEP?: string;
  MIN_STEPS_FOR_REWARD?: string;
  MAX_CREDITED_STEPS_PER_DAY?: string;
  MAX_STEP_COINS_PER_DAY?: string;
  MIN_CLAIM_GAP_SECONDS?: string;

  /** PBKDF2 work factor. Lowered from the OWASP figure to fit the Workers Free
   *  10 ms CPU budget — see README, "Security notes". */
  PBKDF2_ITERATIONS?: string;

  ACCESS_TOKEN_TTL_SECONDS?: string;
  REFRESH_TOKEN_TTL_SECONDS?: string;
  MIN_SUPPORTED_APP_VERSION?: string;

  GENERATIVE_AI_ENABLED?: string;
  AI_PROVIDER?: string;
}

const DEV_FALLBACK_SECRET = "insecure-development-only-secret";

export function jwtSecret(env: Env): string {
  return env.JWT_SECRET || DEV_FALLBACK_SECRET;
}

export function passwordPepper(env: Env): string {
  return env.PASSWORD_PEPPER || DEV_FALLBACK_SECRET;
}

export function adminEmails(env: Env): string[] {
  return (env.ADMIN_EMAILS || "")
    .split(",")
    .map((value) => value.trim().toLowerCase())
    .filter((value) => value.length > 0);
}

export function intVar(env: Env, key: keyof Env, fallback: number): number {
  const raw = env[key];
  if (typeof raw !== "string" || raw.trim() === "") return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function floatVar(env: Env, key: keyof Env, fallback: number): number {
  const raw = env[key];
  if (typeof raw !== "string" || raw.trim() === "") return fallback;
  const parsed = Number.parseFloat(raw);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function boolVar(env: Env, key: keyof Env, fallback: boolean): boolean {
  const raw = env[key];
  if (typeof raw !== "string" || raw.trim() === "") return fallback;
  return raw.trim().toLowerCase() === "true";
}

export function isProduction(env: Env): boolean {
  return env.ENVIRONMENT === "production";
}
