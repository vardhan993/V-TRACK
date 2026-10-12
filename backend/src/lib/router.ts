import type { Env } from "../env";
import { ApiError, errorResponse, jsonResponse, preflightResponse } from "./http";

/**
 * The authenticated caller. Everything here comes from the *server's* copy of
 * the user row, never from anything the request body claimed.
 */
export interface Principal {
  userId: string;
  isAdmin: boolean;
  tokenVersion: number;
  /** Last UTC offset the client reported; drives daily bucketing. */
  timezoneOffsetMinutes: number;
}

export interface Ctx {
  readonly req: Request;
  readonly url: URL;
  readonly env: Env;
  readonly db: D1Database;
  readonly params: Readonly<Record<string, string>>;
  readonly query: URLSearchParams;
  /** Epoch milliseconds when the request started. */
  readonly now: number;
  readonly principal: Principal | null;
}

export type Handler = (ctx: Ctx) => Promise<Response>;

interface Route {
  method: string;
  segments: string[];
  handler: Handler;
  requiresAuth: boolean;
}

export interface RouteOptions {
  /** `required` (default) rejects anonymous callers with 401. */
  auth?: "required" | "optional";
}

/**
 * A deliberately small path router: pattern segments are compared positionally
 * and `:name` captures are exposed through `ctx.params`. There is no wildcard
 * matching, so a route can never accidentally shadow another.
 */
export class Router {
  private readonly routes: Route[] = [];

  add(method: string, path: string, handler: Handler, options: RouteOptions = {}): this {
    this.routes.push({
      method: method.toUpperCase(),
      segments: splitPath(path),
      handler,
      requiresAuth: options.auth !== "optional",
    });
    return this;
  }

  get(path: string, handler: Handler, options?: RouteOptions): this {
    return this.add("GET", path, handler, options);
  }

  post(path: string, handler: Handler, options?: RouteOptions): this {
    return this.add("POST", path, handler, options);
  }

  patch(path: string, handler: Handler, options?: RouteOptions): this {
    return this.add("PATCH", path, handler, options);
  }

  delete(path: string, handler: Handler, options?: RouteOptions): this {
    return this.add("DELETE", path, handler, options);
  }

  match(method: string, path: string): { handler: Handler; requiresAuth: boolean; params: Record<string, string> } | null {
    const segments = splitPath(path);
    const wanted = method.toUpperCase();
    for (const route of this.routes) {
      if (route.method !== wanted || route.segments.length !== segments.length) continue;
      const params: Record<string, string> = {};
      let matched = true;
      for (let i = 0; i < route.segments.length; i++) {
        const pattern = route.segments[i];
        const actual = segments[i];
        if (pattern === undefined || actual === undefined) {
          matched = false;
          break;
        }
        if (pattern.startsWith(":")) {
          params[pattern.slice(1)] = decodeURIComponent(actual);
        } else if (pattern !== actual) {
          matched = false;
          break;
        }
      }
      if (matched) return { handler: route.handler, requiresAuth: route.requiresAuth, params };
    }
    return null;
  }
}

function splitPath(path: string): string[] {
  return path.split("/").filter((segment) => segment.length > 0);
}

/**
 * Dispatches a request through the router. Returns the 405/404 envelopes rather
 * than throwing so the entry point never has to catch routing problems.
 */
export async function dispatch(
  req: Request,
  env: Env,
  router: Router,
  resolvePrincipal: (req: Request, env: Env, now: number) => Promise<Principal | null>,
): Promise<Response> {
  const url = new URL(req.url);

  if (req.method === "OPTIONS") return preflightResponse();

  const matched = router.match(req.method, url.pathname);
  if (!matched) {
    // Distinguish "path unknown" from "method not allowed on a known path".
    const anyMethod = router.match("GET", url.pathname) ?? router.match("POST", url.pathname);
    if (anyMethod) {
      return jsonResponse({ error: { code: "method_not_allowed", message: "Method not allowed.", retryAfter: null } }, 405);
    }
    return jsonResponse({ error: { code: "not_found", message: "No such endpoint.", retryAfter: null } }, 404);
  }

  const now = Date.now();
  let principal: Principal | null = null;
  if (req.headers.get("Authorization") || matched.requiresAuth) {
    principal = await resolvePrincipal(req, env, now);
  }
  if (matched.requiresAuth && !principal) {
    const error = ApiError.unauthorized();
    return errorResponse(error);
  }

  const ctx: Ctx = {
    req,
    url,
    env,
    db: env.DB,
    params: matched.params,
    query: url.searchParams,
    now,
    principal,
  };

  return matched.handler(ctx);
}
