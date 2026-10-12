import type { D1Database } from "@cloudflare/workers-types";

/** Raw `users` row. Snake_case to match the schema exactly — no implicit mapping. */
export interface UserRow {
  id: string;
  email: string;
  email_normalized: string;
  password_hash: string;
  display_name: string;
  avatar_emoji: string | null;
  avatar_url: string | null;
  birth_year: number | null;
  sex: string | null;
  height_cm: number | null;
  weight_kg: number | null;
  fitness_level: string;
  primary_goal: string;
  preferred_activities: string;
  weekly_workout_target: number;
  unit_system: string;
  step_goal: number;
  timezone_offset_minutes: number;
  onboarding_complete: number;
  health_connect_enabled: number;
  share_routes: number;
  share_activity_with_coach: number;
  is_admin: number;
  status: string;
  token_version: number;
  created_at: number;
  updated_at: number;
}

/** Wire shape of `UserDto` on the Android side. */
export interface UserDto {
  id: string;
  email: string;
  displayName: string;
  avatarEmoji: string | null;
  avatarUrl: string | null;
  birthYear: number | null;
  sex: string | null;
  heightCm: number | null;
  weightKg: number | null;
  fitnessLevel: string;
  primaryGoal: string;
  preferredActivities: string[];
  weeklyWorkoutTarget: number;
  unitSystem: string;
  stepGoal: number;
  onboardingComplete: boolean;
  healthConnectEnabled: boolean;
  shareRoutes: boolean;
  shareActivityWithCoach: boolean;
  createdAt: number;
  updatedAt: number;
}

function parseActivities(raw: string): string[] {
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === "string") : [];
  } catch {
    return [];
  }
}

export function toUserDto(row: UserRow): UserDto {
  return {
    id: row.id,
    email: row.email,
    displayName: row.display_name,
    avatarEmoji: row.avatar_emoji,
    avatarUrl: row.avatar_url,
    birthYear: row.birth_year,
    sex: row.sex,
    heightCm: row.height_cm,
    weightKg: row.weight_kg,
    fitnessLevel: row.fitness_level,
    primaryGoal: row.primary_goal,
    preferredActivities: parseActivities(row.preferred_activities),
    weeklyWorkoutTarget: row.weekly_workout_target,
    unitSystem: row.unit_system,
    stepGoal: row.step_goal,
    onboardingComplete: row.onboarding_complete === 1,
    healthConnectEnabled: row.health_connect_enabled === 1,
    shareRoutes: row.share_routes === 1,
    shareActivityWithCoach: row.share_activity_with_coach === 1,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

const USER_COLUMNS = `id, email, email_normalized, password_hash, display_name, avatar_emoji, avatar_url,
  birth_year, sex, height_cm, weight_kg, fitness_level, primary_goal, preferred_activities,
  weekly_workout_target, unit_system, step_goal, timezone_offset_minutes, onboarding_complete,
  health_connect_enabled, share_routes, share_activity_with_coach, is_admin, status, token_version,
  created_at, updated_at`;

export async function findUserById(db: D1Database, id: string): Promise<UserRow | null> {
  return db.prepare(`SELECT ${USER_COLUMNS} FROM users WHERE id = ?1`).bind(id).first<UserRow>();
}

export async function findUserByEmail(db: D1Database, emailNormalized: string): Promise<UserRow | null> {
  return db
    .prepare(`SELECT ${USER_COLUMNS} FROM users WHERE email_normalized = ?1`)
    .bind(emailNormalized)
    .first<UserRow>();
}

export interface ProfilePatch {
  displayName?: string;
  avatarEmoji?: string;
  avatarUrl?: string;
  birthYear?: number;
  sex?: string;
  heightCm?: number;
  weightKg?: number;
  fitnessLevel?: string;
  primaryGoal?: string;
  preferredActivities?: string[];
  weeklyWorkoutTarget?: number;
  unitSystem?: string;
  stepGoal?: number;
  timezoneOffsetMinutes?: number;
  onboardingComplete?: boolean;
  healthConnectEnabled?: boolean;
  shareRoutes?: boolean;
  shareActivityWithCoach?: boolean;
}

/** Whitelist of columns a client is allowed to touch — never interpolated from input. */
const PATCH_COLUMNS: Record<keyof ProfilePatch, string> = {
  displayName: "display_name",
  avatarEmoji: "avatar_emoji",
  avatarUrl: "avatar_url",
  birthYear: "birth_year",
  sex: "sex",
  heightCm: "height_cm",
  weightKg: "weight_kg",
  fitnessLevel: "fitness_level",
  primaryGoal: "primary_goal",
  preferredActivities: "preferred_activities",
  weeklyWorkoutTarget: "weekly_workout_target",
  unitSystem: "unit_system",
  stepGoal: "step_goal",
  timezoneOffsetMinutes: "timezone_offset_minutes",
  onboardingComplete: "onboarding_complete",
  healthConnectEnabled: "health_connect_enabled",
  shareRoutes: "share_routes",
  shareActivityWithCoach: "share_activity_with_coach",
};

function toColumnValue(key: keyof ProfilePatch, value: ProfilePatch[keyof ProfilePatch]): unknown {
  if (typeof value === "boolean") return value ? 1 : 0;
  if (Array.isArray(value)) return JSON.stringify(value);
  return value;
}

export async function updateProfile(
  db: D1Database,
  userId: string,
  patch: ProfilePatch,
  now: number,
): Promise<UserRow | null> {
  const assignments: string[] = [];
  const binds: unknown[] = [];

  for (const key of Object.keys(PATCH_COLUMNS) as Array<keyof ProfilePatch>) {
    const value = patch[key];
    if (value === undefined) continue;
    assignments.push(`${PATCH_COLUMNS[key]} = ?${binds.length + 1}`);
    binds.push(toColumnValue(key, value));
  }

  if (assignments.length > 0) {
    assignments.push(`updated_at = ?${binds.length + 1}`);
    binds.push(now);
    binds.push(userId);
    await db
      .prepare(`UPDATE users SET ${assignments.join(", ")} WHERE id = ?${binds.length}`)
      .bind(...binds)
      .run();
  }

  return findUserById(db, userId);
}

/** Sets is_admin for an address listed in the ADMIN_EMAILS secret. */
export async function syncAdminFlag(db: D1Database, userId: string, shouldBeAdmin: boolean, now: number): Promise<void> {
  await db
    .prepare(`UPDATE users SET is_admin = ?1, updated_at = ?2 WHERE id = ?3`)
    .bind(shouldBeAdmin ? 1 : 0, now, userId)
    .run();
}
