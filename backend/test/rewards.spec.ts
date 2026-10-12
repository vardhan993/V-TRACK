import { describe, expect, it } from "vitest";
import {
  activityDay,
  api,
  dayKey,
  expectError,
  getAdmin,
  ok,
  registerUser,
  workoutPayload,
} from "./helpers";


describe("balance and ledger", () => {
  it("starts empty and then reflects every credited row", async () => {
    const session = await registerUser("ledger-start");
    const empty = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(empty).toMatchObject({ confirmed: 0, pending: 0, earnedToday: 0, lifetimeEarned: 0 });
    expect(empty.engineVersion).toBe("vcoin-rules-1.0.0");

    const page = await ok(await api("/v1/coins/transactions", { token: session.accessToken }));
    expect(page.items).toEqual([]);
    expect(page.nextCursor).toBeNull();
  });

  it("pages transactions with a cursor", async () => {
    const session = await registerUser("ledger-page");
    const today = dayKey();
    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 15_000, 6 * 60)] },
      }),
    );
    await ok(
      await api("/v1/activity/claim/steps", {
        method: "POST",
        token: session.accessToken,
        body: {
          clientEventId: `evt-${session.userId}-page`,
          dayKey: today,
          steps: 15_000,
          activeSeconds: 6 * 3600,
          source: "HEALTH_CONNECT",
          zoneOffsetMinutes: 0,
        },
      }),
    );
    await ok(
      await api("/v1/workouts/sync", {
        method: "POST",
        token: session.accessToken,
        body: [workoutPayload("client-ledger-1")],
      }),
    );
    await ok(
      await api("/v1/activity/claim/workout", {
        method: "POST",
        token: session.accessToken,
        body: { clientEventId: "random-ledger-1", workoutClientId: "client-ledger-1" },
      }),
    );

    const first = await ok(await api("/v1/coins/transactions?limit=1", { token: session.accessToken }));
    expect(first.items).toHaveLength(1);
    expect(first.nextCursor).toBeTruthy();

    const second = await ok(
      await api(`/v1/coins/transactions?limit=1&cursor=${encodeURIComponent(first.nextCursor)}`, {
        token: session.accessToken,
      }),
    );
    expect(second.items).toHaveLength(1);
    expect(second.items[0].id).not.toBe(first.items[0].id);
    expect(second.items[0].amount).toBeGreaterThan(0);

    await expectError(
      await api("/v1/coins/transactions?cursor=not-a-cursor", { token: session.accessToken }),
      400,
      "validation_error",
    );
  });
});

describe("challenges", () => {
  it("derives progress from stored activity and pays at most once per period", async () => {
    const session = await registerUser("challenge-flow");
    const today = dayKey();

    const initial = await ok(await api("/v1/challenges", { token: session.accessToken }));
    expect(initial.items.length).toBeGreaterThanOrEqual(7);
    const daily = initial.items.find((item: any) => item.code === "daily_steps_8k");
    expect(daily).toBeTruthy();
    expect(daily.currentValue).toBe(0);
    expect(daily.completed).toBe(false);
    expect(daily.claimed).toBe(false);
    expect(daily.periodKey).toBe(today);

    const early = await ok(
      await api("/v1/challenges/daily_steps_8k/claim", { method: "POST", token: session.accessToken }),
    );
    expect(early.amount).toBe(0);
    expect(early.status).toBe("IN_PROGRESS");
    expect(early.reason).toMatch(/to go/);

    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 10_000, 6 * 60)] },
      }),
    );

    const mid = await ok(await api("/v1/challenges", { token: session.accessToken }));
    const midDaily = mid.items.find((item: any) => item.code === "daily_steps_8k");
    expect(midDaily.currentValue).toBe(10_000);
    expect(midDaily.completed).toBe(true);

    const claimed = await ok(
      await api("/v1/challenges/daily_steps_8k/claim", { method: "POST", token: session.accessToken }),
    );
    expect(claimed.amount).toBe(3);
    expect(claimed.duplicate).toBe(false);

    const balance = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(balance.confirmed).toBe(3);

    const repeat = await ok(
      await api("/v1/challenges/daily_steps_8k/claim", { method: "POST", token: session.accessToken }),
    );
    expect(repeat.duplicate).toBe(true);
    expect(repeat.amount).toBe(3);
    expect(repeat.balance).toBe(3);

    const after = await ok(await api("/v1/challenges", { token: session.accessToken }));
    const afterDaily = after.items.find((item: any) => item.code === "daily_steps_8k");
    expect(afterDaily.claimed).toBe(true);

    await expectError(
      await api("/v1/challenges/does_not_exist/claim", { method: "POST", token: session.accessToken }),
      404,
      "not_found",
    );
  });
});

describe("achievements", () => {
  it("unlocks from server-side aggregates and pays the bonus once", async () => {
    const session = await registerUser("achievements");
    const today = dayKey();

    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: { days: [activityDay(today, 12_000, 6 * 60)] },
      }),
    );
    await ok(
      await api("/v1/workouts/sync", {
        method: "POST",
        token: session.accessToken,
        body: [workoutPayload("client-ach-1")],
      }),
    );

    const list = await ok(await api("/v1/achievements", { token: session.accessToken }));
    expect(list.length).toBe(10);

    const tenK = list.find((item: any) => item.code === "first_10000_steps");
    expect(tenK.progress).toBe(12_000);
    expect(tenK.threshold).toBe(10_000);
    expect(tenK.unlockedAt).toBeGreaterThan(0);

    const firstWorkout = list.find((item: any) => item.code === "first_workout");
    expect(firstWorkout.unlockedAt).toBeGreaterThan(0);

    const locked = list.find((item: any) => item.code === "steps_500k");
    expect(locked.unlockedAt).toBeNull();
    expect(locked.progress).toBe(12_000);

    const balance = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(balance.confirmed).toBe(4); // 2 for 10k steps + 2 for the first session

    // A second evaluation must not pay again.
    await ok(await api("/v1/achievements", { token: session.accessToken }));
    const again = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(again.confirmed).toBe(4);
  });
});

describe("rewards and redemptions", () => {
  it("publishes the catalogue and refuses to spend more than the balance", async () => {
    const session = await registerUser("rewards-cat");
    const rewards = await ok(await api("/v1/rewards", { token: session.accessToken }));
    expect(rewards.length).toBe(5);
    const cheapest = Math.min(...rewards.map((reward: any) => reward.costCoins));
    expect(cheapest).toBe(25);
    expect(rewards.every((reward: any) => reward.isActive)).toBe(true);

    await expectError(
      await api("/v1/rewards/badge_dark_mode/redeem", {
        method: "POST",
        token: session.accessToken,
        body: { note: "no coins yet" },
      }),
      402,
      "insufficient_balance",
    );

    const redemptions = await ok(await api("/v1/redemptions", { token: session.accessToken }));
    expect(redemptions).toEqual([]);
  });

  it("debits atomically once the balance is sufficient", async () => {
    const session = await registerUser("rewards-buy");
    const admin = await getAdmin();

    await ok(
      await api("/v1/admin/adjust", {
        method: "POST",
        token: admin.accessToken,
        body: { userId: session.userId, amount: 50, reason: "Test grant for redemption flow" },
      }),
    );

    const funded = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(funded.confirmed).toBe(50);
    expect(funded.lifetimeEarned).toBe(50);

    const bought = await ok(
      await api("/v1/rewards/badge_dark_mode/redeem", {
        method: "POST",
        token: session.accessToken,
        body: { note: "for the tests" },
      }),
    );
    expect(bought.rewardId).toBe("badge_dark_mode");
    expect(bought.costCoins).toBe(25);
    expect(bought.status).toBe("PENDING");
    expect(bought.requestedAt).toBeGreaterThan(0);

    const after = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(after.confirmed).toBe(25);
    expect(after.lifetimeEarned).toBe(50);

    const history = await ok(await api("/v1/redemptions", { token: session.accessToken }));
    expect(history).toHaveLength(1);
    expect(history[0].rewardTitle).toBe("Midnight badge");

    const txPage = await ok(await api("/v1/coins/transactions", { token: session.accessToken }));
    const debit = txPage.items.find((item: any) => item.type === "REDEMPTION");
    expect(debit).toBeTruthy();
    expect(debit.amount).toBe(-25);
    expect(debit.balanceAfter).toBe(25);

    // Still not enough for the 60-coin streak shield.
    await expectError(
      await api("/v1/rewards/streak_shield/redeem", { method: "POST", token: session.accessToken, body: {} }),
      402,
      "insufficient_balance",
    );

    await expectError(
      await api("/v1/rewards/unknown_reward/redeem", { method: "POST", token: session.accessToken, body: {} }),
      404,
      "not_found",
    );
  });

  it("never lets an admin debit drive the balance negative", async () => {
    const session = await registerUser("rewards-debit");
    const admin = await getAdmin();

    await expectError(
      await api("/v1/admin/adjust", {
        method: "POST",
        token: admin.accessToken,
        body: { userId: session.userId, amount: -50, reason: "Should not be possible" },
      }),
      409,
      "insufficient_balance",
    );

    const balance = await ok(await api("/v1/coins/balance", { token: session.accessToken }));
    expect(balance.confirmed).toBe(0);
  });
});

describe("admin", () => {
  it("refuses non-admins and then serves stats, users and the audit log", async () => {
    const normal = await registerUser("admin-normal");
    await expectError(await api("/v1/admin/stats", { token: normal.accessToken }), 403, "forbidden");

    const admin = await getAdmin();

    const stats = await ok(await api("/v1/admin/stats", { token: admin.accessToken }));
    expect(stats.users).toBeGreaterThan(0);
    expect(stats.adminUserId).toBe(admin.userId);
    expect(stats.coins_earned).toBeGreaterThanOrEqual(0);

    const users = await ok(await api(`/v1/admin/users?limit=5`, { token: admin.accessToken }));
    expect(users.length).toBeGreaterThan(0);
    expect(users[0]).toHaveProperty("email");

    const detail = await ok(await api(`/v1/admin/users/${admin.userId}`, { token: admin.accessToken }));
    expect(detail.user.id).toBe(admin.userId);
    expect(detail.balance.balance).toBe(0);

    await ok(
      await api(`/v1/admin/users/${normal.userId}/status`, {
        method: "POST",
        token: admin.accessToken,
        body: { status: "SUSPENDED" },
      }),
    );

    // Suspension bumps token_version and flips status, so the old access token
    // stops resolving to a principal.
    const revoked = await api("/v1/me", { token: normal.accessToken });
    expect(revoked.status).toBe(401);


    const audit = await ok(await api("/v1/admin/audit", { token: admin.accessToken }));
    expect(audit.length).toBeGreaterThan(0);
    expect(audit[0].action).toMatch(/user\.status/);
  });

  it("resolves a redemption from the admin side", async () => {
    const session = await registerUser("admin-redeem");
    const admin = await getAdmin();

    await ok(
      await api("/v1/admin/adjust", {
        method: "POST",
        token: admin.accessToken,
        body: { userId: session.userId, amount: 30, reason: "Grant for redemption test" },
      }),
    );
    await ok(
      await api("/v1/rewards/title_pacer/redeem", { method: "POST", token: session.accessToken, body: {} }),
    );

    const pending = await ok(await api("/v1/admin/redemptions", { token: admin.accessToken }));
    const mine = pending.find((row: any) => row.userId === session.userId);
    expect(mine).toBeTruthy();

    await ok(
      await api(`/v1/admin/redemptions/${mine.id}`, {
        method: "PATCH",
        token: admin.accessToken,
        body: { status: "FULFILLED" },
      }),
    );

    const history = await ok(await api("/v1/redemptions", { token: session.accessToken }));
    expect(history[0].status).toBe("FULFILLED");
  });

  it("keeps personal data out of the admin user list query", async () => {
    const admin = await getAdmin();

    const found = await ok(await api(`/v1/admin/users?query=${admin.email}`, { token: admin.accessToken }));
    expect(found).toHaveLength(1);
    expect(found[0].id).toBe(admin.userId);
    expect(found[0]).not.toHaveProperty("passwordHash");
  });
});

describe("offline-friendly surfaces", () => {
  it("returns empty collections rather than an error for a brand new account", async () => {
    const session = await registerUser("fresh-account");
    expect(await ok(await api("/v1/activity/days?from=2020-01-01&to=2020-01-02", { token: session.accessToken }))).toEqual([]);
    expect(await ok(await api("/v1/workouts", { token: session.accessToken }))).toEqual([]);
    expect((await ok(await api("/v1/coins/transactions", { token: session.accessToken }))).items).toEqual([]);
    expect(await ok(await api("/v1/redemptions", { token: session.accessToken }))).toEqual([]);
  });
});

