import type { Env } from "../env";
import { adminEmails, isProduction } from "../env";import { ApiError } from "../lib/http";
import { hashRefreshToken, hashPassword, refreshTtlSeconds, signAccessToken, verifyAccessToken, verifyPassword } from "../lib/crypto";
import { newId, randomToken, sha256Hex } from "../lib/ids";
import type { Principal } from "../lib/router";
import { enforceRateLimit } from "../lib/ratelimit";
import type { UserRow } from "./users";
import { findUserByEmail, findUserById, toUserDto } from "./users";

/**
 * Registration, sign-in, session refresh and sign-out.
 *
 * Deliberately *not* Firebase, Supabase or a third-party identity provider:
 * accounts live in this project's own D1 database, passwords never leave the
 * server as anything but a PBKDF2 hash, refresh tokens are stored only as
 * digests and are rotated (and the old one revoked) on every use.
 */

export interface AuthResponseDto {
  user: ReturnType<typeof toUserDto>;
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface IssueResult {
  response: AuthResponseDto;
}

async function issueSession(env: Env, user: UserRow, now: number): Promise<AuthResponseDto> {
  const nowSeconds = Math.floor(now / 1000);
  const access = await signAccessToken(user.id, user.token_version, user.is_admin === 1, env, nowSeconds);

  const refreshToken = randomToken(32);
  const tokenHash = await hashRefreshToken(refreshToken);
  const ttl = refreshTtlSeconds(env);

  await env.DB.prepare(
    `INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at, user_agent)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6)`,
  )
    .bind(newId(), user.id, tokenHash, now + ttl * 1000, now, "android")
    .run();

  return {
    user: toUserDto(user),
    accessToken: access.token,
    refreshToken,
    expiresIn: access.expiresIn,
  };
}

export async function register(
  env: Env,
  input: { email: string; password: string; displayName: string },
  now: number,
): Promise<AuthResponseDto> {
  const emailNormalized = input.email.trim().toLowerCase();
  await enforceRateLimit(env.DB, `register:${await ipKeyOf(emailNormalized)}`, 10, 3600, now,
    "Too many sign-up attempts from this device. Try again in an hour.");

  const existing = await findUserByEmail(env.DB, emailNormalized);
  if (existing) {
    throw ApiError.conflict("email_taken", "An account with that email already exists.");
  }

  const passwordHash = await hashPassword(input.password, env);
  const userId = newId();

  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO users (id, email, email_normalized, password_hash, display_name, created_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)`,
      ).bind(userId, input.email.trim(), emailNormalized, passwordHash, input.displayName.trim(), now),
      env.DB.prepare(
        `INSERT INTO coin_accounts (user_id, balance, lifetime_earned, lifetime_spent, updated_at)
         VALUES (?1, 0, 0, 0, ?2)`,
      ).bind(userId, now),
    ]);
  } catch (error) {
    if (String(error).includes("UNIQUE constraint failed")) {
      throw ApiError.conflict("email_taken", "An account with that email already exists.");
    }
    throw error;
  }

  const user = await findUserById(env.DB, userId);
  if (!user) throw new Error("user vanished immediately after insert");
  return issueSession(env, user, now);
}

export async function login(
  env: Env,
  input: { email: string; password: string },
  now: number,
): Promise<AuthResponseDto> {
  const emailNormalized = input.email.trim().toLowerCase();
  await enforceRateLimit(env.DB, `login:${await ipKeyOf(emailNormalized)}`, 15, 900, now,
    "Too many sign-in attempts. Try again in a few minutes.");

  const user = await findUserByEmail(env.DB, emailNormalized);

  // Always run a verification so a missing account and a wrong password take
  // comparable time and cannot be told apart by an attacker.
  const hash = user?.password_hash ?? "$pbkdf2-sha256$1000$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  const ok = await verifyPassword(input.password, hash, env);
  if (!user || !ok) {
    throw new ApiError(401, "invalid_credentials", "That email and password combination is not correct.");
  }
  if (user.status !== "ACTIVE") {
    throw ApiError.forbidden("This account is no longer active.");
  }

  return issueSession(env, user, now);
}

export async function refresh(env: Env, refreshToken: string, now: number): Promise<AuthResponseDto> {
  await enforceRateLimit(env.DB, `refresh:${await sha256Hex(refreshToken).then((value) => value.slice(0, 16))}`,
    60, 900, now, "Too many session refreshes. Try again shortly.");

  const tokenHash = await hashRefreshToken(refreshToken);
  const row = await env.DB.prepare(
    `SELECT id, user_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ?1`,
  )
    .bind(tokenHash)
    .first<{ id: string; user_id: string; expires_at: number; revoked_at: number | null }>();

  if (!row || row.revoked_at !== null || row.expires_at <= now) {
    throw ApiError.unauthorized("Your session expired. Please sign in again.");
  }

  const user = await findUserById(env.DB, row.user_id);
  if (!user || user.status !== "ACTIVE") {
    throw ApiError.unauthorized("Your session expired. Please sign in again.");
  }

  const response = await issueSession(env, user, now);

  // Rotate: revoke the presented token and remember which one replaced it, so
  // a stolen refresh token stops working the moment it is used.
  const replacementHash = await hashRefreshToken(response.refreshToken);
  await env.DB.batch([
    env.DB.prepare(
      `UPDATE refresh_tokens SET revoked_at = ?1, replaced_by = (SELECT id FROM refresh_tokens WHERE token_hash = ?2)
       WHERE id = ?3`,
    ).bind(now, replacementHash, row.id),
  ]);

  return response;
}

export async function logout(env: Env, refreshToken: string, now: number): Promise<void> {
  const tokenHash = await hashRefreshToken(refreshToken);
  await env.DB.prepare(
    `UPDATE refresh_tokens SET revoked_at = ?1 WHERE token_hash = ?2 AND revoked_at IS NULL`,
  )
    .bind(now, tokenHash)
    .run();
}

/**
 * Resolves the `Authorization: Bearer` header into a principal, verifying the
 * signature, the expiry *and* that the token version still matches the account
 * (which is what makes "sign out everywhere" work).
 */
export async function resolvePrincipal(req: Request, env: Env, now: number): Promise<Principal | null> {
  const header = req.headers.get("Authorization");
  if (!header || !header.toLowerCase().startsWith("bearer ")) return null;

  const token = header.slice(7).trim();
  if (token.length === 0) return null;

  const nowSeconds = Math.floor(now / 1000);
  let payload;
  try {
    payload = await verifyAccessToken(token, env, nowSeconds);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    return null;
  }

  const user = await findUserById(env.DB, payload.sub);
  if (!user || user.status !== "ACTIVE") return null;
  if (user.token_version !== payload.tv) {
    throw ApiError.unauthorized("Your session expired. Please sign in again.");
  }

  const isAdmin = user.is_admin === 1 && adminEmails(env).includes(user.email_normalized);
  return {
    userId: user.id,
    isAdmin,
    tokenVersion: user.token_version,
    timezoneOffsetMinutes: user.timezone_offset_minutes,
  };
}

export async function requireAdmin(principal: Principal | null): Promise<Principal> {
  if (!principal) throw ApiError.unauthorized();
  if (!principal.isAdmin) throw ApiError.forbidden("Administrator access is required.");
  return principal;
}

/**
 * Rate-limit bucket key: a salted digest rather than the raw identifier, so no
 * email address or IP is ever written to the rate_limits table or a log line.
 *
 * The salt is generated on first use, not at module scope: workerd rejects
 * random number generation outside a request handler.
 */
let bucketSalt: string | null = null;

async function ipKeyOf(subject: string): Promise<string> {
  if (bucketSalt === null) bucketSalt = randomToken(16);
  return (await sha256Hex(`${subject}:${bucketSalt}`)).slice(0, 24);
}

/** True when the deployment supplies real secrets (i.e. not a local dev run). */
export function hasRealSecrets(env: Env): boolean {
  return Boolean(env.JWT_SECRET && env.PASSWORD_PEPPER);
}

/** Refuses to serve traffic when a production deploy forgot `wrangler secret put`. */
export function assertSecretsConfigured(env: Env): void {
  if (isProduction(env) && !hasRealSecrets(env)) {
    throw new ApiError(503, "misconfigured", "Server secrets are not configured for this deployment.");
  }
}
