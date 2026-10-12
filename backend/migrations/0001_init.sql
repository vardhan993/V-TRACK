-- V-Track initial schema (Cloudflare D1 / SQLite).
--
-- Conventions:
--  * ids are opaque text (server-generated UUIDs) so a client can never collide
--    with another user's row;
--  * every user-scoped table carries user_id so a query can always be scoped by
--    the authenticated principal, never by anything the client sends;
--  * timestamps are epoch milliseconds (UTC) unless the column says *_key;
--  * day_key is YYYY-MM-DD in the user's reported UTC offset;
--  * monetary-ish values are integers: V-Coins are whole units, never floats.

/* ------------------------------------------------------------------ users */

CREATE TABLE users (
  id                        TEXT PRIMARY KEY,
  email                     TEXT NOT NULL,
  email_normalized          TEXT NOT NULL,
  password_hash             TEXT NOT NULL,
  display_name              TEXT NOT NULL,
  avatar_emoji              TEXT,
  avatar_url                TEXT,
  birth_year                INTEGER,
  sex                       TEXT,
  height_cm                 REAL,
  weight_kg                 REAL,
  fitness_level             TEXT NOT NULL DEFAULT 'BEGINNER',
  primary_goal              TEXT NOT NULL DEFAULT 'STAY_ACTIVE',
  preferred_activities      TEXT NOT NULL DEFAULT '[]',
  weekly_workout_target     INTEGER NOT NULL DEFAULT 3,
  unit_system               TEXT NOT NULL DEFAULT 'METRIC',
  step_goal                 INTEGER NOT NULL DEFAULT 8000,
  timezone_offset_minutes   INTEGER NOT NULL DEFAULT 0,
  onboarding_complete       INTEGER NOT NULL DEFAULT 0,
  health_connect_enabled    INTEGER NOT NULL DEFAULT 0,
  share_routes              INTEGER NOT NULL DEFAULT 0,
  share_activity_with_coach INTEGER NOT NULL DEFAULT 1,
  is_admin                  INTEGER NOT NULL DEFAULT 0,
  status                    TEXT NOT NULL DEFAULT 'ACTIVE',
  token_version             INTEGER NOT NULL DEFAULT 1,
  created_at                INTEGER NOT NULL,
  updated_at                INTEGER NOT NULL,
  CHECK (step_goal BETWEEN 1000 AND 50000),
  CHECK (weekly_workout_target BETWEEN 0 AND 14),
  CHECK (timezone_offset_minutes BETWEEN -840 AND 840),
  CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE UNIQUE INDEX users_email_unique ON users(email_normalized);

-- Refresh tokens are stored only as a SHA-256 digest: a database leak does not
-- hand over usable credentials.
CREATE TABLE refresh_tokens (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  TEXT NOT NULL,
  expires_at  INTEGER NOT NULL,
  created_at  INTEGER NOT NULL,
  revoked_at  INTEGER,
  replaced_by TEXT,
  user_agent  TEXT
);

CREATE UNIQUE INDEX refresh_tokens_hash_unique ON refresh_tokens(token_hash);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens(user_id, expires_at);

/* -------------------------------------------------------------- activity */

CREATE TABLE activity_days (
  user_id             TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day_key             TEXT NOT NULL,
  steps               INTEGER NOT NULL DEFAULT 0,
  verified_steps      INTEGER NOT NULL DEFAULT 0,
  distance_meters     REAL NOT NULL DEFAULT 0,
  calories            REAL NOT NULL DEFAULT 0,
  active_minutes      INTEGER NOT NULL DEFAULT 0,
  source              TEXT NOT NULL DEFAULT 'DEVICE',
  zone_offset_minutes INTEGER NOT NULL DEFAULT 0,
  buckets_json        TEXT NOT NULL DEFAULT '[]',
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  PRIMARY KEY (user_id, day_key),
  CHECK (steps >= 0 AND steps <= 60000),
  CHECK (verified_steps >= 0 AND verified_steps <= steps),
  CHECK (zone_offset_minutes BETWEEN -840 AND 840)
);

CREATE INDEX activity_days_user_day_idx ON activity_days(user_id, day_key DESC);

/* --------------------------------------------------------------- workouts */

CREATE TABLE workouts (
  id                   TEXT PRIMARY KEY,
  user_id              TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id            TEXT NOT NULL,
  activity_type        TEXT NOT NULL,
  title                TEXT,
  started_at           INTEGER NOT NULL,
  ended_at             INTEGER NOT NULL,
  duration_seconds     INTEGER NOT NULL,
  distance_meters      REAL NOT NULL DEFAULT 0,
  calories             REAL NOT NULL DEFAULT 0,
  avg_pace_sec_per_km  REAL NOT NULL DEFAULT 0,
  avg_speed_kmh        REAL NOT NULL DEFAULT 0,
  steps                INTEGER NOT NULL DEFAULT 0,
  elevation_gain_meters REAL NOT NULL DEFAULT 0,
  indoor               INTEGER NOT NULL DEFAULT 0,
  manual               INTEGER NOT NULL DEFAULT 0,
  notes                TEXT,
  perceived_effort     INTEGER,
  verified             INTEGER NOT NULL DEFAULT 0,
  coins_awarded        INTEGER NOT NULL DEFAULT 0,
  day_key              TEXT NOT NULL,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL,
  CHECK (duration_seconds >= 0 AND duration_seconds <= 86400),
  CHECK (distance_meters >= 0),
  CHECK (perceived_effort IS NULL OR perceived_effort BETWEEN 1 AND 10)
);

CREATE UNIQUE INDEX workouts_client_unique ON workouts(user_id, client_id);
CREATE INDEX workouts_user_started_idx ON workouts(user_id, started_at DESC);
CREATE INDEX workouts_user_day_idx ON workouts(user_id, day_key);

CREATE TABLE workout_sets (
  id               TEXT PRIMARY KEY,
  workout_id       TEXT NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id        TEXT NOT NULL,
  exercise_id      TEXT NOT NULL,
  set_index        INTEGER NOT NULL,
  reps             INTEGER,
  weight_kg        REAL,
  duration_seconds INTEGER,
  rest_seconds     INTEGER,
  rpe              INTEGER,
  is_warmup        INTEGER NOT NULL DEFAULT 0,
  completed_at     INTEGER NOT NULL,
  UNIQUE (workout_id, client_id)
);

CREATE INDEX workout_sets_workout_idx ON workout_sets(workout_id, exercise_id, set_index);
CREATE INDEX workout_sets_user_idx ON workout_sets(user_id, completed_at DESC);

/* ---------------------------------------------------------- V-Coin ledger */

-- Materialised balance. Every mutation happens inside the same D1 batch as the
-- matching ledger row, so the two can never drift, and the CHECK constraint
-- makes an overdraft impossible even under concurrency.
CREATE TABLE coin_accounts (
  user_id          TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  balance          INTEGER NOT NULL DEFAULT 0,
  lifetime_earned  INTEGER NOT NULL DEFAULT 0,
  lifetime_spent   INTEGER NOT NULL DEFAULT 0,
  updated_at       INTEGER NOT NULL,
  CHECK (balance >= 0),
  CHECK (lifetime_earned >= 0 AND lifetime_spent >= 0)
);

-- Append-only ledger. (user_id, client_event_id) being UNIQUE is the single
-- arbiter of idempotency: replaying a claim can only ever produce one row.
CREATE TABLE coin_transactions (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_event_id TEXT NOT NULL,
  type            TEXT NOT NULL,
  amount          INTEGER NOT NULL,
  reason          TEXT NOT NULL,
  activity_ref    TEXT,
  status          TEXT NOT NULL DEFAULT 'CONFIRMED',
  balance_after   INTEGER,
  created_at      INTEGER NOT NULL,
  UNIQUE (user_id, client_event_id),
  CHECK (status IN ('CONFIRMED', 'PENDING', 'REVERSED'))
);

CREATE INDEX coin_transactions_user_created_idx
  ON coin_transactions(user_id, created_at DESC, id DESC);

-- Per-user, per-day earning state: caps, minimum claim spacing and the
-- "already awarded today" figure the incremental step credit is derived from.
CREATE TABLE daily_coin_earnings (
  user_id                 TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day_key                 TEXT NOT NULL,
  step_coins_awarded      INTEGER NOT NULL DEFAULT 0,
  workout_rewards_awarded INTEGER NOT NULL DEFAULT 0,
  last_step_claim_at      INTEGER,
  created_at              INTEGER NOT NULL,
  updated_at              INTEGER NOT NULL,
  PRIMARY KEY (user_id, day_key),
  CHECK (step_coins_awarded >= 0 AND workout_rewards_awarded >= 0)
);

/* ------------------------------------------------- challenges & achievements */

CREATE TABLE challenges (
  code         TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  description  TEXT NOT NULL,
  type         TEXT NOT NULL,
  period       TEXT NOT NULL,
  target_value REAL NOT NULL,
  reward_coins INTEGER NOT NULL,
  is_active    INTEGER NOT NULL DEFAULT 1,
  sort_order   INTEGER NOT NULL DEFAULT 0,
  CHECK (period IN ('DAILY', 'WEEKLY')),
  CHECK (reward_coins > 0)
);

CREATE TABLE challenge_claims (
  user_id        TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  challenge_code TEXT NOT NULL REFERENCES challenges(code),
  period_key     TEXT NOT NULL,
  claimed_at     INTEGER NOT NULL,
  transaction_id TEXT,
  PRIMARY KEY (user_id, challenge_code, period_key)
);

CREATE TABLE achievements (
  code         TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  description  TEXT NOT NULL,
  icon         TEXT NOT NULL,
  category     TEXT NOT NULL,
  metric       TEXT NOT NULL,
  threshold    REAL NOT NULL,
  reward_coins INTEGER NOT NULL DEFAULT 0,
  sort_order   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE achievement_unlocks (
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  achievement_code TEXT NOT NULL REFERENCES achievements(code),
  progress         REAL NOT NULL,
  unlocked_at      INTEGER NOT NULL,
  transaction_id   TEXT,
  PRIMARY KEY (user_id, achievement_code)
);

/* --------------------------------------------------------------- rewards */

CREATE TABLE rewards (
  id               TEXT PRIMARY KEY,
  title            TEXT NOT NULL,
  description      TEXT NOT NULL,
  category         TEXT NOT NULL,
  cost_coins       INTEGER NOT NULL,
  emoji            TEXT,
  stock            INTEGER,
  fulfillment_note TEXT,
  is_active        INTEGER NOT NULL DEFAULT 1,
  sort_order       INTEGER NOT NULL DEFAULT 0,
  CHECK (cost_coins > 0)
);

CREATE TABLE redemptions (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reward_id    TEXT NOT NULL REFERENCES rewards(id),
  cost_coins   INTEGER NOT NULL,
  status       TEXT NOT NULL DEFAULT 'PENDING',
  note         TEXT,
  requested_at INTEGER NOT NULL,
  resolved_at  INTEGER,
  CHECK (status IN ('PENDING', 'FULFILLED', 'CANCELLED'))
);

CREATE INDEX redemptions_user_idx ON redemptions(user_id, requested_at DESC);

/* ----------------------------------------------------------------- coach */

CREATE TABLE coach_plans (
  id                TEXT PRIMARY KEY,
  user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  week_start_day_key TEXT NOT NULL,
  payload_json      TEXT NOT NULL,
  engine_version    TEXT NOT NULL,
  generated_by      TEXT NOT NULL,
  created_at        INTEGER NOT NULL,
  UNIQUE (user_id, week_start_day_key)
);

/* ------------------------------------------------------------ operations */

CREATE TABLE rate_limits (
  bucket_key   TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL
);

CREATE TABLE admin_audit_log (
  id            TEXT PRIMARY KEY,
  admin_user_id TEXT,
  action        TEXT NOT NULL,
  detail        TEXT,
  created_at    INTEGER NOT NULL
);

/* --------------------------------------------------------------- seeds */

INSERT INTO challenges (code, title, description, type, period, target_value, reward_coins, is_active, sort_order) VALUES
  ('daily_steps_8k',  '8,000 steps',        'Reach 8,000 steps today',                              'STEPS',          'DAILY',  8000,    3, 1, 10),
  ('daily_steps_12k', '12,000 steps',       'Reach 12,000 steps today',                             'STEPS',          'DAILY',  12000,   5, 1, 20),
  ('daily_active_30', '30 active minutes',  'Log 30 active minutes today',                          'ACTIVE_MINUTES','DAILY',  30,      3, 1, 30),
  ('daily_workout',   'Move for 30 minutes','Complete any workout of 30 minutes or more today',      'WORKOUT_MINUTES','DAILY',  30,      4, 1, 40),
  ('weekly_steps_50k','50,000 steps',       'Walk 50,000 steps this week',                          'STEPS',          'WEEKLY', 50000,  12, 1, 50),
  ('weekly_workouts', 'Three workouts',     'Complete three workouts this week',                    'WORKOUT_COUNT',  'WEEKLY', 3,       10, 1, 60),
  ('weekly_active_150','150 active minutes','Reach 150 active minutes this week',                   'ACTIVE_MINUTES','WEEKLY', 150,     12, 1, 70);

INSERT INTO achievements (code, title, description, icon, category, metric, threshold, reward_coins, sort_order) VALUES
  ('first_10000_steps',    '10,000 steps',        'Get 10,000 steps in a day',            '👟', 'steps',    'BEST_DAY_STEPS',       10000,   2, 10),
  ('steps_100k',           '100k club',           'Walk 100,000 lifetime steps',          '🚶', 'steps',    'TOTAL_STEPS',          100000,  3, 20),
  ('steps_500k',           'Half a million',      'Walk 500,000 lifetime steps',          '🏅', 'steps',    'TOTAL_STEPS',          500000, 10, 30),
  ('first_workout',        'First session',       'Complete your first workout',          '🎯', 'workouts', 'WORKOUT_COUNT',        1,       2, 40),
  ('workouts_25',          '25 sessions',         'Complete 25 workouts',                '💪', 'workouts', 'WORKOUT_COUNT',        25,      8, 50),
  ('streak_7',             'Week streak',         'Stay active 7 days in a row',          '🔥', 'streak',   'BEST_STREAK',          7,       5, 60),
  ('streak_30',            'Month streak',        'Stay active 30 days in a row',         '🌟', 'streak',   'BEST_STREAK',          30,     20, 70),
  ('distance_marathon',    'Marathon distance',   'Cover 42.2 km in a single activity',   '🏃', 'distance', 'LONGEST_WORKOUT_M',    42195,  15, 80),
  ('sets_500',             '500 sets',            'Log 500 strength sets',                '🏋️', 'strength', 'TOTAL_SETS',           500,     8, 90),
  ('active_minutes_1000',  '1,000 active minutes','Accumulate 1,000 active minutes',       '⏱️', 'minutes',  'TOTAL_ACTIVE_MINUTES', 1000,    8, 100);

INSERT INTO rewards (id, title, description, category, cost_coins, emoji, stock, fulfillment_note, is_active, sort_order) VALUES
  ('badge_dark_mode',    'Midnight badge',      'A cosmetic profile badge for your V-Track profile', 'COSMETIC', 25,  '🌙', NULL, 'Applied to your profile automatically.',      1, 10),
  ('streak_shield',      'Streak shield',       'Protects today''s streak if you miss tomorrow',     'PERK',     60,  '🛡️', NULL, 'Applied automatically to your next missed day.', 1, 20),
  ('avatar_frame_green', 'Electric frame',      'An electric-green avatar frame',                    'COSMETIC', 40,  '⚡', NULL, 'Applied to your profile automatically.',      1, 30),
  ('coach_theme_night',  'Coach night theme',   'Unlocks the night coaching theme',                  'COSMETIC', 35,  '🌙', NULL, 'Applied to your profile automatically.',      1, 40),
  ('title_pacer',        'Title: Pacer',        'Show "Pacer" next to your name',                    'COSMETIC', 30,  '🏅', NULL, 'Applied to your profile automatically.',      1, 50);
