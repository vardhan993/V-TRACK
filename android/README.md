# V-Track Android

Kotlin + Jetpack Compose client for V-Track. MVVM, Hilt, Room (offline cache), Retrofit +
kotlinx.serialization (API), WorkManager (background sync), Health Connect (fitness data).

V-Coins earned in the app are **virtual credits only** — no cash value, no withdrawal, no
monetary exchange of any kind.

## Requirements

- JDK 21 (`JAVA_HOME` must point at it before running Gradle)
- Android SDK with **compileSdk 36** (required by `androidx.health.connect:connect-client:1.1.0`)
- `targetSdk 35`, `minSdk 26`, single `app` module
- The Gradle wrapper is committed; no local Gradle install needed

On this machine:

```powershell
set JAVA_HOME=D:\tools\jdk-21.0.12.1+1
```

## Build and test

```powershell
cd android
gradlew.bat testDebugUnitTest     # JVM unit tests
gradlew.bat assembleDebug         # -> app\build\outputs\apk\debug\app-debug.apk
```

Verified on this machine: `testDebugUnitTest` → **11 passed, 0 failures**; `assembleDebug` →
BUILD SUCCESSFUL.

The only unit tests are `app/src/test/.../domain/engine/CoinRulesParityTest.kt`, which pins
the client's earning estimate to the same golden values the backend enforces. There are **no**
`androidTest` instrumentation tests — the app has not been run on a device or emulator in this
session.

## Pointing at a backend

Two Gradle properties in `android/gradle.properties` are read into `BuildConfig`:

| Property | Default | Meaning |
| --- | --- | --- |
| `vtrack.apiBaseUrl` | `http://10.0.2.2:8787/` | API base URL. `10.0.2.2` is the host loopback as seen from the Android emulator |
| `vtrack.allowCleartext` | `false` | Must be `true` to send HTTP (non-TLS) requests |

Override per machine in `~/.gradle/gradle.properties` rather than editing the committed file.

For a deployed backend:

```properties
vtrack.apiBaseUrl=https://vtrack-api.<your-subdomain>.workers.dev/
vtrack.allowCleartext=false
```

### Cleartext HTTP

`app/src/main/res/xml/network_security_config.xml` allows cleartext **only** for
`10.0.2.2`, `localhost` and `127.0.0.1` — the emulator loopback hosts used for local
development. Production traffic must be HTTPS. Even when `vtrack.allowCleartext=true`, the
network security config still blocks cleartext to any other host.

## Permissions

Health Connect (read): steps, distance, active calories, exercise. Plus
`ACTIVITY_RECOGNITION`, foreground-service permissions for the step and workout services, and
the usual `INTERNET` / `ACCESS_NETWORK_STATE`. Location permissions are used only for the live
workout GPS track and are not required for step counting.

The first launch routes through onboarding, which asks for the Health Connect grant before
reading any activity.

## Architecture

```
ui/          Compose screens + ViewModels (home, earn, coach, analytics, workout, profile,
             auth, onboarding, health, navigation)
domain/      Pure Kotlin - RewardRules (coin estimate), engine, models
data/        local/ (Room DAOs, entities), remote/ (Retrofit API + DTOs), repository/
tracking/    StepTrackingService, StepSensorTracker, LiveWorkoutService, LocationTracker,
             HealthConnectManager, WorkScheduler
work/        WorkManager workers for background sync
di/          Hilt modules
core/        Result wrapper, dispatchers, time
```

### Offline behaviour

Room is the source of truth for the UI. Activity is read from Health Connect, written to Room,
and pushed to the API by a WorkManager worker when there is a network connection. Until the
server responds, the coin balance shown on screen is explicitly an estimate computed by
`domain/engine/RewardRules.kt` from local data — the app never presents it as a confirmed
balance. Confirmed values come back from `/v1/coins/balance` after sync.

The API contract (camelCase JSON, `Authorization: Bearer`, error envelope
`{ error: { code, message, retryAfter } }`, `X-Client: android`) is implemented in
`data/remote/`.

## Free-tier note

The client is deliberately chatty-free: one batched sync call per day-window, claims keyed on
stable `clientEventId`s so retries are free, and no polling. This is what keeps a real user
comfortably inside the Worker's 100k requests/day.

## Known gaps

- No instrumentation tests and no CI pipeline.
- Not run on a physical device or emulator in this session — only compilation and JVM unit
  tests were executed.
- The server's reward catalogue and challenge list are fetched and rendered, but there is no
  in-app admin surface; administration is via the API (see `backend/README.md`).
