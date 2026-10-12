/**
 * HTTP primitives: JSON responses, CORS, and the single error type that every
 * route converts into the `{ error: { code, message, retryAfter } }` envelope the
 * Android client (`ApiErrorDto`) understands.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "ApiError";
  }

  static badRequest(code: string, message: string): ApiError {
    return new ApiError(400, code, message);
  }

  static unauthorized(message = "Sign in to continue."): ApiError {
    return new ApiError(401, "unauthorized", message);
  }

  static forbidden(message = "You do not have access to that."): ApiError {
    return new ApiError(403, "forbidden", message);
  }

  static notFound(message = "Not found."): ApiError {
    return new ApiError(404, "not_found", message);
  }

  static conflict(code: string, message: string): ApiError {
    return new ApiError(409, code, message);
  }

  static tooMany(message: string, retryAfterSeconds: number): ApiError {
    return new ApiError(429, "rate_limited", message, retryAfterSeconds);
  }
}

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PATCH, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, X-Client, X-Client-Version",
  "Access-Control-Max-Age": "86400",
  "Access-Control-Expose-Headers": "Retry-After",
};

export function jsonResponse(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...CORS_HEADERS,
      ...extraHeaders,
    },
  });
}

export function emptyResponse(status = 204): Response {
  return new Response(null, {
    status,
    headers: { ...CORS_HEADERS },
  });
}

export function errorResponse(error: unknown): Response {
  if (error instanceof ApiError) {
    const headers: Record<string, string> = {};
    if (error.retryAfterSeconds !== undefined) {
      headers["Retry-After"] = String(error.retryAfterSeconds);
    }
    return jsonResponse(
      { error: { code: error.code, message: error.message, retryAfter: error.retryAfterSeconds ?? null } },
      error.status,
      headers,
    );
  }

  // Never leak internals: log the real cause, return a generic envelope.
  console.error("unhandled", error instanceof Error ? error.stack : String(error));
  return jsonResponse({ error: { code: "internal_error", message: "Something went wrong.", retryAfter: null } }, 500);
}

export function preflightResponse(): Response {
  return new Response(null, { status: 204, headers: CORS_HEADERS });
}
