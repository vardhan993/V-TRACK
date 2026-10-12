# V-Track API

Cloudflare Worker + D1 (SQLite) backend for V-Track. TypeScript, Zod, Hono-free — the routing
layer is ~200 lines in `src/lib/router.ts`. Free tier only: 10 ms CPU per invocation, 100k
requests/day, 5M D1 row reads/day, 100k D1 row writes/day.

The server is the **only** authority on V-Coins. The Android app shows an estimate; every
credit and every debit is decided here against a ledger in D1.

## Requirements

- Node 20+ (developed and verified on Node 22)
- `npm install` — Wrangler, Vitest and TypeScript all come from `devDependencies`
- A Cloudflare account **only** for `npm run deploy`; local development and tests need none

## Scripts

| Script | What it does |
| --- | --- |
| `npm run typecheck` | `tsc --noEmit` |
| `npm test` | Vitest against a Miniflare-backed D1 (102 tests) |
| `npm run test:watch` | Same, watch mode |
| `npm run migrate:local` | Apply `migrations/*.sql` to the **local** D1 |
| `npm run migrate:remote` | Apply migrations to the **production** D1 |
| `npm run db:create` | Create the production D1 database (prints the `database_id`) |
| `npm run dev` | `wrangler dev` on `http://127.0.0.1:8787` |
| `npm run deploy` | Deploy the Worker |

## Local development

```bash
npm install
npm run typecheck
npm test

npm run migrate:local     # REQUIRED - see the warning below
npm run dev               # http://127.0.0.1:8787
```

> **`wrangler dev` does not apply migrations.** If you skip `npm run migrate:local` the API
> starts fine and then fails closed on `/v1/auth/register` and `/v1/auth/login` (429
> `rate_limited`), because the rate-limiter cannot see its `rate_limits` table. Always migrate
> first.

### Secrets for local runs

Create `backend/.dev.vars` (already gitignored — never commit it):

```
JWT_SECRET = "local-dev-jwt-secret-change-me-0123456789"
PASSWORD_PEPPER = "local-dev-pepper-change-me-0123456789abcdef"
ADMIN_EMAILS = "admin@smoke.test"
```

`ADMIN_EMAILS` is a comma-separated list of email addresses that may receive admin routes.
Membership is checked on **every** admin request against `is_admin=1` *and* this list, so
removing an email from the list revokes access without touching the database.

`wrangler dev` reloads `.dev.vars` on restart. Changing `PASSWORD_PEPPER` invalidates every
existing password hash — that is intentional, and is why production sets it once with
`wrangler secret put PASSWORD_PEPPER`.

If you create the admin user *before* the pepper is configured, the hash will not verify later.
The reliable order is: start `wrangler dev` with `.dev.vars` in place → register the account →
stop the server → `npx wrangler d1 execute vtrack --local --command "UPDATE users SET is_admin = 1 WHERE email_normalized = 'admin@example.test'"` → restart.

### Tests

`npm test` runs 102 tests across 5 files. Each test file gets an isolated Miniflare instance
with the migrations from `migrations/0001_init.sql` applied automatically by
`test/setup.ts` (it imports the SQL with `?raw` and applies it via `db.batch()`; it does **not**
use `@cloudflare/vitest-pool-workers`' `readD1Migrations`, which would pull `node:process` into
the workerd runtime and crash).

Test-only bindings live in `vitest.config.ts`: `PBKDF2_ITERATIONS=1000` (so the suite is not
waiting 15 000 iterations per password) and a fixed `ADMIN_EMAILS`. **Do not** lower
`PBKDF2_ITERATIONS` in `wrangler.jsonc` — the suite deliberately overrides it.

You will see a deprecation notice about `@cloudflare/vitest-pool-workers` being renamed to
`@cloudflare/vitest-plugin`. It is cosmetic; the current API works.

## Deploying

```bash
npm run db:create
# copy the printed database_id into wrangler.jsonc -> d1_databases[0].database_id

npx wrangler secret put JWT_SECRET
npx wrangler secret put PASSWORD_PEPPER
npx wrangler secret put ADMIN_EMAILS

npm run migrate:remote
npm run deploy
```

`src/index.ts` calls `assertSecretsConfigured(env)` on every request: in a non-local
environment a missing secret is a hard 500 rather than a half-working deployment.

Use long random values for `JWT_SECRET` and `PASSWORD_PEPPER` (32+ bytes from a CSPRNG):

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
```

## Configuration

All non-secret knobs live in `wrangler.jsonc` under `vars`. Secrets are `JWT_SECRET`,
`PASSWORD_PEPPER`, `ADMIN_EMAILS`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `ENVIRONMENT` | `development` | `production` enables the secret check |
| `COIN_ENGINE_VERSION` | `vcoin-rules-1.0.0` | Surfaced to the client; bump when rules change |
| `COINS_PER_STEP` | `0.00095` | Coins per step |
| `MIN_STEPS_FOR_REWARD` | `1000` | Minimum verified steps in a day before step rewards pay |
| `MAX_CREDITED_STEPS_PER_DAY` | `20000` | Sync rejects days above this |
| `MAX_STEP_COINS_PER_DAY` | `8` | Daily step-coin cap |
| `MIN_CLAIM_GAP_SECONDS` | `900` | Minimum gap between step claims |
| `PBKDF2_ITERATIONS` | `15000` | Password KDF cost (~4.5 ms/verification) |
| `ACCESS_TOKEN_TTL_SECONDS` | `1800` | 30 minutes |
| `REFRESH_TOKEN_TTL_SECONDS` | `2592000` | 30 days |
| `MIN_SUPPORTED_APP_VERSION` | `1` | Returned by `/v1/config` |
| `GENERATIVE_AI_ENABLED` | `false` | Off. The coach is a rules engine |
| `AI_PROVIDER` | `none` | Unused while the above is false |

`limits.cpu_ms: 10` in `wrangler.jsonc` records the free-plan budget explicitly; Cloudflare
enforces it regardless.

## API surface

All routes take `Authorization: Bearer <accessToken>` except `/health`, `/v1/config`,
`/v1/auth/*`, and `/v1/coach/ask`. Responses are camelCase; errors are
`{ error: { code, message, retryAfter } }`.

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/health` | Liveness |
| GET | `/v1/config` | Coin rules for the client estimate |
| POST | `/v1/auth/register` | Rate limited: 10/hour per salted email+IP |
| POST | `/v1/auth/login` | Rate limited: 15/15min per salted email+IP |
| POST | `/v1/auth/refresh` | Rotating refresh token; 60/15min per token digest |
| POST | `/v1/auth/logout` | Revokes the presented refresh token |
| GET | `/v1/me` | Profile |
| PATCH | `/v1/me` | Update profile / coach-sharing toggle |
| DELETE | `/v1/me` | Soft-delete (`status = 'DELETED'`) |
| GET | `/v1/me/export` | Full personal-data export |
| POST | `/v1/activity/sync` | Upsert day buckets; rejects implausible days |
| GET | `/v1/activity/days` | Synced days |
| POST | `/v1/activity/claim/steps` | Step reward; idempotent on `clientEventId` |
| POST | `/v1/activity/claim/workout` | Workout reward; idempotent on `clientEventId` |
| POST | `/v1/workouts/sync` | Batch upsert; idempotent on `clientId` |
| GET | `/v1/workouts` | List |
| PATCH | `/v1/workouts/:clientId` | Edit notes / effort |
| GET | `/v1/coins/balance` | `confirmed`, `earnedToday`, `remainingToday`, … |
| GET | `/v1/coins/transactions` | Ledger, cursor-paginated |
| GET | `/v1/challenges` | Active challenges with progress |
| POST | `/v1/challenges/:id/claim` | Claim a completed challenge |
| GET | `/v1/achievements` | Catalogue with per-user progress |
| GET | `/v1/rewards` | Reward catalogue |
| POST | `/v1/rewards/:id/redeem` | Creates a `PENDING` redemption |
| GET | `/v1/redemptions` | Own redemption history |
| GET | `/v1/coach/plan` / `/v1/coach/review` | Gated on `share_activity_with_coach` |
| POST | `/v1/coach/ask` | Rules-engine answer, ungated |
| GET | `/v1/admin/stats` | Instance counters |
| GET | `/v1/admin/users` · `/v1/admin/users/:id` | User directory / detail |
| POST | `/v1/admin/users/:id/status` | `ACTIVE` / `SUSPENDED` / `DELETED` |
| POST | `/v1/admin/adjust` | Manual credit/debit with an audit row |
| GET | `/v1/admin/redemptions?status=` | Fulfilment queue |
| PATCH | `/v1/admin/redemptions/:id` | `FULFILLED` / `REJECTED` |
| GET | `/v1/admin/audit` | Audit log |

Admin list endpoints return bare arrays, not `{ items: [] }`.

## Design notes

### Coin authority

`src/services/coinRules.ts` is the authoritative ruleset, mirrored by
`android/.../domain/engine/RewardRules.kt`. Both are pinned to the same golden values by
`test/coin-rules.spec.ts` and `android/.../CoinRulesParityTest.kt`.

Step rewards are paid from the **server-side** aggregates in `activity_days`, never from the
claim payload's raw step count. The payload's `steps` field is only a cross-check.

### Anti-fraud

- `UNIQUE (user_id, client_event_id)` on `coin_transactions` — replaying a claim returns the
  original transaction with `duplicate: true` instead of paying twice.
- Workout claims key on the server-derived `workout:<client_id>`, so a user cannot claim the
  same workout through two different event ids.
- `challenge_claims` is inserted first inside the same `db.batch()`, so a double-click cannot
  pay a challenge twice.
- `CHECK (balance >= 0)` on `coin_accounts` — an overdrawn balance is rejected by the
  database, not just by application code.
- Plausibility gate (`isPlausible`): `stepsPerMinute > 220` is rejected unless the day is
  60+ minutes; `maxPlausibleStepsPerDay = 60000`.

### Rate limiting

Two layers, both `429` with `retryAfter`:

1. **In-memory sliding window**, 300 requests/minute per IP, applied to every request in
   `src/index.ts`. Zero database cost, per-isolate (so the effective limit is per-isolate, not
   global — acceptable for a free-tier API).
2. **D1 fixed window** on credential endpoints only (`src/lib/ratelimit.ts`). Bucket keys are
   salted SHA-256 digests, never raw emails or IPs, so the `rate_limits` table cannot be mined
   for user data.

The D1 limiter **fails closed**: if the `rate_limits` table is missing, register/login return
429 rather than serving unthrottled.

### Password storage

`PBKDF2-HMAC-SHA256`, format `pbkdf2-sha256$<iter>$<saltB64>$<hashB64>`, plus a server-side
`PASSWORD_PEPPER`. Login always runs a verification even when the account does not exist, so a
missing email and a wrong password take comparable time.

The 15 000-iteration default is a deliberate trade against the 10 ms CPU budget: ~4.5 ms per
verification leaves headroom for the rest of the request. Raising it past ~40 000 will start
to trip the free-plan CPU limit on `/v1/auth/login`.

### Sessions

HS256 JWT access tokens (30 min) and rotating refresh tokens stored as SHA-256 digests
(30 days). Rotating means each refresh revokes the presented token and issues a new one;
reusing a revoked token revokes the whole `token_version`, which invalidates every outstanding
access token for that user. That is what makes `POST /v1/admin/users/:id/status` take effect
immediately rather than after 30 minutes.

## Free-tier budget

| Limit | Free tier | Where it bites |
| --- | --- | --- |
| Worker CPU | 10 ms/invocation | PBKDF2 on login; keep iterations at 15 000 |
| Worker requests | 100,000/day | Sync and claim traffic |
| D1 row reads | 5,000,000/day | Transaction list, challenge progress |
| D1 row writes | 100,000/day | Sync upserts, claims, audit rows |
| D1 storage | 5 GB | One user ≈ 50 KB/year |
| D1 databases | 1 | No per-tenant isolation |

The in-memory request limiter exists specifically so that a request flood costs zero D1 reads.

## Known limitations

- No tests run against a deployed Worker; verification is `wrangler dev` + local D1.
- The in-memory rate limit is per-isolate and resets on isolate eviction.
- `redemptions` has no TTL; a `PENDING` row stays pending until an admin resolves it.
- No webhook/push infrastructure — fulfilment is admin-driven.
- `PATCH /v1/workouts/:clientId` does not re-run claim eligibility for already-claimed
  workouts.
