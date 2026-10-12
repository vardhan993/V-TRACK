# V-Track

A fitness tracker that rewards movement with **V-Coins** — virtual in-app credits that can be
spent on cosmetic and convenience rewards. Sweatcoin-inspired, but with none of the advertising
or real-money mechanics: V-Coins have no monetary value, cannot be withdrawn, and are never
exchanged for cash.

Everything runs on a free tier:

| Piece | Stack | Cost |
| --- | --- | --- |
| Android app | Kotlin · Jetpack Compose · MVVM · Hilt · Room · Retrofit | free |
| Backend | Cloudflare Workers · D1 (SQLite) · TypeScript · Zod | free tier |
| Fitness data | Health Connect (on-device), with a manual-entry fallback | free |

No Firebase, no Supabase, no paid APIs, no telemetry SDKs.

## Repository layout

```
backend/   Cloudflare Worker + D1 schema/migrations + API + test suite
android/   The Android client (app module, single-activity Compose UI)
```

## How the pieces fit together

The client records activity with Health Connect (or manual entry), caches it in Room, and syncs
it to the API when a connection is available. The **server is the only authority on V-Coins**:
the app shows an *estimate* computed from a local copy of the rules, but every credit and every
debit is decided by the API against its own ledger.

That split matters. A modified APK, a replayed request, or a tampered local database can change
what the app *displays*; it cannot change the balance the server will report.

The earning rules are mirrored in two places on purpose:

- `android/.../domain/engine/RewardRules.kt` — the client estimate
- `backend/src/services/coinRules.ts` — the authoritative copy

Both are pinned to the same golden values by
`android/app/src/test/.../CoinRulesParityTest.kt` and `backend/test/coin-rules.spec.ts`, so
changing one without the other fails a build.

## Verified status

Everything below was executed on this machine, not inferred:

| Check | Command | Result |
| --- | --- | --- |
| Backend type check | `npm run typecheck` (in `backend/`) | clean |
| Backend unit/API tests | `npm test` (in `backend/`) | **102 passed**, 5 files |
| Android unit tests | `gradlew testDebugUnitTest` (in `android/`) | **11 passed** |
| Android debug APK | `gradlew assembleDebug` (in `android/`) | built |
| Live end-to-end against `wrangler dev` | see below | passed |

The live run (a real `wrangler dev` instance with a real local D1) exercised: registration,
sign-in, profile read/export, activity sync, step claim + replay dedup, workout sync, workout
claim + replay dedup, balance, transaction ledger, challenge claim, achievement unlocks,
reward catalogue, an under-funded redemption being refused, an admin adjustment, a funded
redemption, admin fulfilment, the audit log, account suspension invalidating live tokens, and
the coach plan/ask endpoints.

**Not verified / not implemented:**

- No instrumentation (`androidTest`) tests, and no CI pipeline.
- The app has not been run on a physical device or an emulator in this session; only compilation
  and JVM unit tests were executed.
- End-to-end runs used `wrangler dev` (local D1). A deployed Worker has not been exercised.
- Push notifications, social features, and any generative AI are out of scope
  (`GENERATIVE_AI_ENABLED=false`; the coach is a deterministic rules engine).

## Quick start

Backend:

```bash
cd backend
npm install
npm run typecheck
npm test
npm run migrate:local     # creates the local D1 schema
npm run dev               # http://127.0.0.1:8787
```

Android:

```bash
cd android
gradlew testDebugUnitTest
gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
```

Full setup, deployment and configuration details are in
[`backend/README.md`](backend/README.md) and [`android/README.md`](android/README.md).

## A note on V-Coins

V-Coins are game credits. They are not a currency, not a token, and not redeemable for money or
goods of value. The reward catalogue is cosmetic (badges, themes, coach styles). The daily caps
in the earning rules exist to keep it a game rather than a farming exercise.
