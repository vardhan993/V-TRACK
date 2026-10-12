import { SELF, env } from "cloudflare:test";
import type { Env } from "../src/env";

/** The test worker's bindings, typed against the real `Env` interface. */
export const testEnv = env as unknown as Env;

/** Convenience wrapper so every spec talks to the API the same way. */
export async function api(
  path: string,
  init: { method?: string; body?: unknown; token?: string } = {},
): Promise<Response> {
  const headers: Record<string, string> = { "Content-Type": "application/json", "X-Client": "android" };
  if (init.token) headers.Authorization = `Bearer ${init.token}`;
  return SELF.fetch(`http://vtrack.test${path}`, {
    method: init.method ?? "GET",
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
}

export async function ok<T = any>(response: Response): Promise<T> {
  const text = await response.text();
  if (!response.ok) throw new Error(`expected 2xx, got ${response.status}: ${text}`);
  return JSON.parse(text) as T;
}

export async function expectError(response: Response, status: number, code?: string): Promise<any> {
  const body: any = await response.json();
  if (response.status !== status) throw new Error(`expected ${status}, got ${response.status}: ${JSON.stringify(body)}`);
  if (code && body?.error?.code !== code) {
    throw new Error(`expected code ${code}, got ${body?.error?.code}: ${JSON.stringify(body)}`);
  }
  return body;
}

let counter = 0;
export function uniqueEmail(prefix = "user"): string {
  counter += 1;
  return `${prefix}-${counter}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}@example.test`;
}

export interface Session {
  accessToken: string;
  refreshToken: string;
  userId: string;
  email: string;
}

export const PASSWORD = "correct-horse-battery-staple";

export async function registerUser(prefix = "user", email = uniqueEmail(prefix)): Promise<Session> {
  const body = await ok(
    await api("/v1/auth/register", {
      method: "POST",
      body: { email, password: PASSWORD, displayName: "Test Person" },
    }),
  );
  return { accessToken: body.accessToken, refreshToken: body.refreshToken, userId: body.user.id, email };
}

/**
 * The address listed in the `ADMIN_EMAILS` test binding. Because D1 persists
 * for the whole run, this registers once and reuses the account afterwards.
 */
export async function getAdmin(): Promise<Session> {
  const email = "admin@example.test";
  const login = { email, password: PASSWORD };

  const existing = await api("/v1/auth/login", { method: "POST", body: login });
  if (!existing.ok) {
    const created = await api("/v1/auth/register", {
      method: "POST",
      body: { ...login, displayName: "Admin Person" },
    });
    if (!created.ok) throw new Error(`could not create the admin account: ${created.status}`);
  }

  await testEnv.DB.prepare(`UPDATE users SET is_admin = 1, status = 'ACTIVE' WHERE email_normalized = ?1`)
    .bind(email)
    .run();

  const fresh = await ok(await api("/v1/auth/login", { method: "POST", body: login }));
  return { accessToken: fresh.accessToken, refreshToken: fresh.refreshToken, userId: fresh.user.id, email };
}

/** Today in the user's stored UTC offset, formatted as a day key. */
export function dayKey(offsetMinutes = 0, at = Date.now()): string {
  return new Date(at + offsetMinutes * 60_000).toISOString().slice(0, 10);
}

export function pastDayKey(daysAgo: number, offsetMinutes = 0, at = Date.now()): string {
  return dayKey(offsetMinutes, at - daysAgo * 86_400_000);
}

/** Shifts a day key by whole days (pure calendar maths, no timezone involved). */
export function addDaysKey(dayKeyValue: string, days: number): string {
  return new Date(Date.parse(`${dayKeyValue}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10);
}

/** Monday of the week containing [dayKeyValue] — mirrors `weekStartKey` in src/lib/time.ts. */
export function weekStartOf(dayKeyValue: string): string {
  const date = new Date(`${dayKeyValue}T00:00:00Z`);
  const daysSinceMonday = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - daysSinceMonday);
  return date.toISOString().slice(0, 10);
}

/**
 * A plausible activity day. `activeMinutes` is the device-measured active time
 * for the whole day — the same unit the sync schema expects — and the hourly
 * buckets add up to the daily totals exactly.
 */
export function activityDay(dayKeyValue: string, steps: number, activeMinutes: number) {
  const perHour = Math.floor(steps / 24);
  const bucketSteps = (hour: number) => (hour === 0 ? steps - perHour * 23 : perHour);
  return {
    dayKey: dayKeyValue,
    steps,
    distanceMeters: Number((steps * 0.714).toFixed(2)),
    calories: Math.min(Math.round(activeMinutes * 5), 20_000),
    activeMinutes,
    source: "HEALTH_CONNECT",
    zoneOffsetMinutes: 0,
    buckets: Array.from({ length: 24 }, (_, hour) => ({
      hour,
      steps: bucketSteps(hour),
      distanceMeters: Number((bucketSteps(hour) * 0.714).toFixed(2)),
      calories: 0,
      activeSeconds: Math.round((activeMinutes * 60) / 24),
    })),
  };
}

export function workoutPayload(
  clientId: string,
  overrides: Partial<{
    activityType: string;
    durationSeconds: number;
    startedAt: number;
    endedAt: number;
    distanceMeters: number;
    calories: number;
    title: string;
  }> = {},
) {
  const startedAt = overrides.startedAt ?? Date.now() - 3_600_000;
  const durationSeconds = overrides.durationSeconds ?? 1_800;
  return {
    clientId,
    activityType: overrides.activityType ?? "RUNNING",
    title: overrides.title ?? "Morning run",
    startedAt,
    endedAt: overrides.endedAt ?? startedAt + durationSeconds * 1000,
    durationSeconds,
    distanceMeters: overrides.distanceMeters ?? 5_000,
    calories: overrides.calories ?? 350,
    avgPaceSecPerKm: 360,
    avgSpeedKmh: 10,
    steps: 0,
    elevationGainMeters: 0,
    indoor: false,
    manual: false,
    notes: null,
    perceivedEffort: 6,
    sets: [],
  };
}
