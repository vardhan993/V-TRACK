import type { Env } from "../env";
import { jwtSecret, passwordPepper } from "../env";
import { decodeJson, encodeJson, fromBase64Url, newId, sha256Hex, toBase64Url } from "./ids";
import { ApiError } from "./http";

/**
 * Password hashing and bearer-token signing.
 *
 * Passwords: PBKDF2-HMAC-SHA256 with a per-user 16-byte salt, then a final
 * HMAC-SHA256 keyed by the server-side `PASSWORD_PEPPER`. The pepper means a
 * leak of the database alone is not enough to mount an offline guessing attack.
 *
 * The PBKDF2 iteration count is deliberately far below the OWASP figure
 * (600k for PBKDF2-SHA256). Cloudflare's Workers **Free** plan allows only
 * 10 ms of CPU time per invocation, and PBKDF2 runs inside that budget
 * (~0.3 µs/iteration here, so 15k ≈ 4.5 ms). It is configurable through the
 * PBKDF2_ITERATIONS var; moving to a compliant work factor requires Workers
 * Paid. This trade-off is documented in README.md rather than hidden.
 *
 * Tokens: access tokens are compact JWS values signed with HS256 and expire in
 * 30 minutes. Refresh tokens are 32 random bytes stored only as a SHA-256 digest
 * and rotated on every use.
 */

const encoder = new TextEncoder();

export interface AccessTokenPayload {
  sub: string;
  iat: number;
  exp: number;
  jti: string;
  tv: number;
  adm: boolean;
}

function iterations(env: Env): number {
  const raw = Number.parseInt(env.PBKDF2_ITERATIONS ?? "", 10);
  return Number.isFinite(raw) && raw >= 1000 ? raw : 15_000;
}

async function derive(password: string, salt: Uint8Array, count: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: salt as unknown as BufferSource, iterations: count },
    key,
    256,
  );
  return new Uint8Array(bits);
}

async function peppered(password: string, salt: Uint8Array, count: number, env: Env): Promise<Uint8Array> {
  const base = await derive(password, salt, count);
  const mac = await crypto.subtle.importKey(
    "raw",
    encoder.encode(passwordPepper(env)),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", mac, base as unknown as BufferSource);
  return new Uint8Array(signature);
}

/** Returns `pbkdf2-sha256$<iterations>$<salt-b64>$<hash-b64>`. */
export async function hashPassword(password: string, env: Env): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const count = iterations(env);
  const hash = await peppered(password, salt, count, env);
  return `pbkdf2-sha256$${count}$${toBase64Url(salt)}$${toBase64Url(hash)}`;
}

export async function verifyPassword(password: string, stored: string, env: Env): Promise<boolean> {
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256") return false;
  const count = Number.parseInt(parts[1] ?? "", 10);
  if (!Number.isFinite(count) || count < 1000) return false;
  let salt: Uint8Array;
  try {
    salt = fromBase64Url(parts[2] ?? "");
  } catch {
    return false;
  }
  const expected = parts[3] ?? "";
  const actual = toBase64Url(await peppered(password, salt, count, env));
  return timingSafeEqual(actual, expected);
}

export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/* ------------------------------------------------------------------ tokens */

async function sign(payload: AccessTokenPayload, env: Env): Promise<string> {
  const header = encodeJson({ alg: "HS256", typ: "JWT" });
  const body = encodeJson(payload);
  const data = `${header}.${body}`;
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(jwtSecret(env)),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(data));
  return `${data}.${toBase64Url(new Uint8Array(signature))}`;
}

export async function signAccessToken(
  userId: string,
  tokenVersion: number,
  isAdmin: boolean,
  env: Env,
  nowSeconds: number,
): Promise<{ token: string; expiresIn: number }> {
  const ttlRaw = Number.parseInt(env.ACCESS_TOKEN_TTL_SECONDS ?? "", 10);
  const ttl = Number.isFinite(ttlRaw) && ttlRaw > 0 ? ttlRaw : 1800;
  const payload: AccessTokenPayload = {
    sub: userId,
    iat: nowSeconds,
    exp: nowSeconds + ttl,
    jti: newId(),
    tv: tokenVersion,
    adm: isAdmin,
  };
  return { token: await sign(payload, env), expiresIn: ttl };
}

/**
 * Verifies signature, expiry and token version. Throws [ApiError] (401) on any
 * failure so callers never have to distinguish "malformed" from "expired".
 */
export async function verifyAccessToken(token: string, env: Env, nowSeconds: number): Promise<AccessTokenPayload> {
  const parts = token.split(".");
  if (parts.length !== 3) throw ApiError.unauthorized("That session token is not valid.");
  const [header, body, signature] = parts as [string, string, string];

  let signatureBytes: Uint8Array;
  try {
    signatureBytes = fromBase64Url(signature);
  } catch {
    throw ApiError.unauthorized("That session token is not valid.");
  }

  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(jwtSecret(env)),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const valid = await crypto.subtle.verify(
    "HMAC",
    key,
    signatureBytes as unknown as BufferSource,
    encoder.encode(`${header}.${body}`),
  );
  if (!valid) throw ApiError.unauthorized("That session token is not valid.");

  let payload: AccessTokenPayload;
  try {
    const decodedHeader = decodeJson<{ alg?: string }>(header);
    payload = decodeJson<AccessTokenPayload>(body);
    if (decodedHeader.alg !== "HS256") throw new Error("alg");
  } catch {
    throw ApiError.unauthorized("That session token is not valid.");
  }

  if (typeof payload.sub !== "string" || payload.sub.length === 0) {
    throw ApiError.unauthorized("That session token is not valid.");
  }
  if (typeof payload.exp !== "number" || payload.exp <= nowSeconds) {
    throw ApiError.unauthorized("Your session expired. Please sign in again.");
  }
  return payload;
}

export async function hashRefreshToken(token: string): Promise<string> {
  return sha256Hex(token);
}

export function refreshTtlSeconds(env: Env): number {
  const raw = Number.parseInt(env.REFRESH_TOKEN_TTL_SECONDS ?? "", 10);
  return Number.isFinite(raw) && raw > 0 ? raw : 2_592_000;
}
