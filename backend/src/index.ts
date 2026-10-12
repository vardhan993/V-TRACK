import type { Env } from "./env";
import { ApiError, errorResponse, preflightResponse } from "./lib/http";
import { dispatch } from "./lib/router";
import { memoryRateLimit } from "./lib/ratelimit";
import { buildRouter } from "./routes";
import { assertSecretsConfigured, resolvePrincipal } from "./services/auth";

/**
 * V-Track API — Cloudflare Worker entry point.
 *
 * Responsibilities kept here (and nowhere else):
 *  - refuse to serve when a production deploy is missing its secrets;
 *  - a per-IP in-memory rate limit before any route runs (zero database cost);
 *  - convert anything thrown by a route into the `{ error: {code,message,retryAfter} }`
 *    envelope the Android client understands.
 */

const router = buildRouter();

/** Requests allowed per minute, per client IP, per isolate. */
const GLOBAL_REQUESTS_PER_MINUTE = 300;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      if (req.method === "OPTIONS") return preflightResponse();

      assertSecretsConfigured(env);

      const clientIp = req.headers.get("CF-Connecting-IP") ?? "unknown";
      const wait = memoryRateLimit(`ip:${clientIp}`, GLOBAL_REQUESTS_PER_MINUTE, Date.now());
      if (wait !== null) throw ApiError.tooMany("Too many requests. Please slow down.", wait);

      return await dispatch(req, env, router, resolvePrincipal);
    } catch (error) {
      return errorResponse(error);
    }
  },
};
