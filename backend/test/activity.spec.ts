import { describe, expect, it } from "vitest";
import {
  activityDay,
  api,
  dayKey,
  expectError,
  ok,
  pastDayKey,
  registerUser,
  workoutPayload,
} from "./helpers";

describe("activity sync", () => {
  it("stores uploaded days and reports verified steps", async () => {
    const session = await registerUser("act-sync");
    const today = dayKey();

    const sync = await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 10_000, 60), activityDay(pastDayKey(1), 4_000, 25)] },
      }),
    );
    expect(sync.acceptedDays).toBe(2);
    expect(sync.rejected).toEqual([]);
    expect(sync.verifiedSteps[today]).toBe(10_000);

    const days = await ok(
      await api(`/v1/activity/days?from=${pastDayKey(2)}&to=${today}`, { token: session.accessToken }),
    );
    expect(days).toHaveLength(2);
    expect(days[1].dayKey).toBe(today);
    expect(days[1].steps).toBe(10_000);
    expect(days[1].verifiedSteps).toBe(10_000);
    expect(days[1].updatedAt).toBeGreaterThan(0);
  });

  it("rejects a payload that fails validation", async () => {
    const session = await registerUser("act-bad");
    await expectError(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay("not-a-day", 100, 5)] },
      }),
      400,
      "validation_error",
    );
  });

  it("keeps the raw number and rescales an impossible distance", async () => {
    const session = await registerUser("act-implausible");
    const today = dayKey();

    const broken = activityDay(today, 50_000, 60);
    broken.buckets = []; // buckets that contradict nothing, but total is implausible vs distance
    broken.distanceMeters = 1; // ratio 0.00002 → distance gets rescaled, steps kept

    const sync = await ok(
      await api("/v1/activity/sync", { method: "POST", token: session.accessToken, body: { days: [broken] } }),
    );
    expect(sync.acceptedDays).toBe(1);

    const days = await ok(
      await api(`/v1/activity/days?from=${today}&to=${today}`, { token: session.accessToken }),
    );
    expect(days[0].steps).toBe(50_000);
    expect(days[0].distanceMeters).toBeGreaterThan(30_000);
  });
});

describe("step claims", () => {
  it("credits the day once and treats a replay as a duplicate", async () => {
    const session = await registerUser("claim-once");
    const today = dayKey();
    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 10_000, 6 * 60)] },
      }),
    );

    const first = await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-a`,
          dayKey: today,
          steps: 10_000,
          activeSeconds: 6 * 3600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    // floor(10_000 * 0.00095) = 9, then the 8-coin daily ceiling applies.
    expect(first.amount).toBe(8);
    expect(first.balance).toBe(8);
    expect(first.duplicate).toBe(false);
    expect(first.status).toBe("CONFIRMED");

    const replay = await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-a`,
          dayKey: today,
          steps: 10_000,
          activeSeconds: 6 * 3600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    expect(replay.duplicate).toBe(true);
    expect(replay.amount).toBe(8);
    expect(replay.balance).toBe(8);
  });

  it("enforces the minimum gap between distinct claims", async () => {
    const session = await registerUser("claim-gap");
    const today = dayKey();
    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 12_000, 6 * 60)] },
      }),
    );

    await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-gap-1`,
          dayKey: today,
          steps: 12_000,
          activeSeconds: 6 * 3600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );

    const second = await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-gap-2`,
          dayKey: today,
          steps: 12_000,
          activeSeconds: 6 * 3600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    expect(second.amount).toBe(0);
    expect(second.status).toBe("NO_REWARD");
    expect(second.reason).toMatch(/at most every 15 minutes/i);
  });

  it("caps a day at the step-coin ceiling", async () => {
    const session = await registerUser("claim-cap");
    const today = dayKey();
    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 60_000, 10 * 60)] },
      }),
    );

    const claim = await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-cap`,
          dayKey: today,
          steps: 60_000,
          activeSeconds: 10 * 3600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    // 20,000 credited steps × 0.00095 = 19 → capped to 8.
    expect(claim.amount).toBe(8);

    const balance = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(balance.confirmed).toBe(8);
    expect(balance.dailyStepCap).toBe(8);
    expect(balance.remainingToday).toBe(0);
    expect(balance.lifetimeEarned).toBe(8);
  });

  it("refuses days below the earning floor", async () => {
    const session = await registerUser("claim-floor");
    const today = dayKey();
    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 500, 10)] },
      }),
    );

    const claim = await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-floor`,
          dayKey: today,
          steps: 500,
          activeSeconds: 600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    expect(claim.amount).toBe(0);
    expect(claim.status).toBe("NO_REWARD");
    expect(claim.reason).toMatch(/1,000 verified steps/);
  });

  it("refuses an implausible payload without crediting anything", async () => {
    const session = await registerUser("claim-impl");
    const today = dayKey();
    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 20_000, 600)] },
      }),
    );

    const claim = await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-impl`,
          dayKey: today,
          steps: 20_000,
          activeSeconds: 60,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    expect(claim.amount).toBe(0);
    expect(claim.reason).toMatch(/does not look plausible/);

    const balance = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(balance.confirmed).toBe(0);
  });

  it("refuses to credit a day that was never synced", async () => {
    const session = await registerUser("claim-unsynced");
    const claim = await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-none`,
          dayKey: dayKey(),
          steps: 9_000,
          activeSeconds: 3_600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    expect(claim.amount).toBe(0);
    expect(claim.reason).toMatch(/No synced activity/);
  });
});

describe("workouts", () => {
  it("syncs, lists, patches and rewards a session exactly once", async () => {
    const session = await registerUser("workout-flow");

    const sync = await ok(
      await api("/v1/workouts/sync", {
        method: "POST",
        token: session.accessToken,
        body: [workoutPayload("client-w1", { activityType: "RUNNING", durationSeconds: 3_600 })],
      }),
    );
    expect(sync.accepted).toBe(1);
    expect(sync.duplicates).toBe(0);
    expect(sync.workouts).toHaveLength(1);
    expect(sync.workouts[0].clientId).toBe("client-w1");
    expect(sync.workouts[0].verified).toBe(true);

    // Re-syncing the same client id must not create a second row.
    const again = await ok(
      await api("/v1/workouts/sync", {
        method: "POST",
        token: session.accessToken,
        body: [workoutPayload("client-w1", { activityType: "RUNNING", durationSeconds: 3_600 })],
      }),
    );
    expect(again.accepted).toBe(0);
    expect(again.duplicates).toBe(1);

    const list = await ok(await api("/v1/workouts", { token: session.accessToken }));
    expect(list).toHaveLength(1);

    const patched = await ok(
      await api("/v1/workouts/client-w1", {
        method: "PATCH",
        token: session.accessToken,
        body: { notes: "Felt strong", perceivedEffort: 7 },
      }),
    );
    expect(patched.notes).toBe("Felt strong");
    expect(patched.perceivedEffort).toBe(7);

    const claim = await ok(
      await api("/v1/activity/claim/workout", {
        method: "POST",
        token: session.accessToken,
        body: { clientEventId: "random-uuid-from-client-1", workoutClientId: "client-w1" },
      }),
    );
    // 3_600s RUNNING: (1 + 2) × 1.2 = 3.6 → 3
    expect(claim.amount).toBe(3);
    expect(claim.balance).toBe(3);

    // A second attempt with a *different* client id must not pay again: the
    // ledger key is derived from the workout, not from the request.
    const retry = await ok(
      await api("/v1/activity/claim/workout", {
        method: "POST",
        token: session.accessToken,
        body: { clientEventId: "random-uuid-from-client-2", workoutClientId: "client-w1" },
      }),
    );
    expect(retry.duplicate).toBe(true);
    expect(retry.amount).toBe(3);
    expect(retry.balance).toBe(3);
  });

  it("refuses to reward a session shorter than the minimum", async () => {
    const session = await registerUser("workout-short");
    await ok(
      await api("/v1/workouts/sync", {
        method: "POST",
        token: session.accessToken,
        body: [workoutPayload("client-short", { activityType: "RUNNING", durationSeconds: 300 })],
      }),
    );

    const claim = await ok(
      await api("/v1/activity/claim/workout", {
        method: "POST",
        token: session.accessToken,
        body: { clientEventId: "random-uuid-short", workoutClientId: "client-short" },
      }),
    );
    expect(claim.amount).toBe(0);
    expect(claim.reason).toMatch(/at least 10 minutes/);
  });

  it("reports an unsynced workout instead of inventing a reward", async () => {
    const session = await registerUser("workout-unknown");
    const claim = await ok(
      await api("/v1/activity/claim/workout", {
        method: "POST",
        token: session.accessToken,
        body: { clientEventId: "random-uuid-unknown", workoutClientId: "never-synced" },
      }),
    );
    expect(claim.amount).toBe(0);
    expect(claim.reason).toMatch(/not been synced/);
  });

  it("404s a patch for a workout that does not exist", async () => {
    const session = await registerUser("workout-404");
    await expectError(
      await api("/v1/workouts/nope", { method: "PATCH", token: session.accessToken, body: { notes: "x" } }),
      404,
      "not_found",
    );
  });
});
