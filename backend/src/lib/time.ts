/** Calendar helpers. All day keys are `YYYY-MM-DD`; week keys are the Monday of that week. */

const DAY_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function isDayKey(value: string): boolean {
  if (!DAY_KEY_PATTERN.test(value)) return false;
  const [year, month, day] = value.split("-").map((part) => Number.parseInt(part, 10));
  if (year === undefined || month === undefined || day === undefined) return false;
  if (month < 1 || month > 12 || day < 1 || day > 31) return false;
  const probe = new Date(Date.UTC(year, month - 1, day));
  return probe.getUTCFullYear() === year && probe.getUTCMonth() === month - 1 && probe.getUTCDate() === day;
}

/** Day key for an instant in a fixed UTC offset (minutes east of UTC). */
export function dayKeyAt(epochMillis: number, offsetMinutes: number): string {
  const shifted = new Date(epochMillis + offsetMinutes * 60_000);
  return shifted.toISOString().slice(0, 10);
}

/** Day key that a client-supplied offset implies for "now". */
export function todayKey(offsetMinutes: number, now: number): string {
  return dayKeyAt(now, offsetMinutes);
}

/** Monday (as a day key) of the week containing [dayKey]. */
export function weekStartKey(dayKey: string): string {
  const [year, month, day] = dayKey.split("-").map((part) => Number.parseInt(part, 10));
  const date = new Date(Date.UTC(year ?? 1970, (month ?? 1) - 1, day ?? 1));
  const weekday = date.getUTCDay(); // 0 = Sunday
  const daysSinceMonday = (weekday + 6) % 7;
  date.setUTCDate(date.getUTCDate() - daysSinceMonday);
  return date.toISOString().slice(0, 10);
}

/** Inclusive list of day keys from `from` to `to`, capped at [maxDays]. */
export function dayRange(from: string, to: string, maxDays = 400): string[] {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return [];
  const days: string[] = [];
  for (let cursor = start; cursor <= end && days.length < maxDays; cursor += 86_400_000) {
    days.push(new Date(cursor).toISOString().slice(0, 10));
  }
  return days;
}

/** Adds `days` to a day key. */
export function addDays(dayKey: string, days: number): string {
  const base = Date.parse(`${dayKey}T00:00:00Z`);
  return new Date(base + days * 86_400_000).toISOString().slice(0, 10);
}

export function clampOffsetMinutes(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(-840, Math.min(840, Math.round(value)));
}
