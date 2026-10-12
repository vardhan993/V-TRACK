import { describe, expect, it } from "vitest";
import { api, expectError, ok, registerUser, PASSWORD } from "./helpers";

describe("auth", () => {
  it("registers an account and returns a usable session", async () => {
    const session = await registerUser("auth-basic");
    expect(session.accessToken.length).toBeGreaterThan(20);
    expect(session.refreshToken.length).toBeGreaterThan(20);

    const me = await ok(await api("/v1/me", { token: session.accessToken }));
    expect(me.id).toBe(session.userId);
    expect(me.email).toBe(session.email);
    expect(me.stepGoal).toBe(8000);
    expect(me.onboardingComplete).toBe(false);
  });

  it("rejects a duplicate registration with 409 email_taken", async () => {
    const email = `dup-${Date.now().toString(36)}@example.test`;
    await registerUser("auth-dup", email);
    const body = await expectError(
      await api("/v1/auth/register", { method: "POST", body: { email, password: PASSWORD, displayName: "Copy" } }),
      409,
      "email_taken",
    );
    expect(body.error.retryAfter).toBeNull();
  });

  it("validates the payload before touching the database", async () => {
    await expectError(
      await api("/v1/auth/register", {
        method: "POST",
        body: { email: "not-an-email", password: "short", displayName: "" },
      }),
      400,
      "validation_error",
    );
  });

  it("signs in and refuses a wrong password", async () => {
    const session = await registerUser("auth-login");

    const bad = await expectError(
      await api("/v1/auth/login", { method: "POST", body: { email: session.email, password: "wrong-password-here" } }),
      401,
      "invalid_credentials",
    );
    expect(bad.error.message).toMatch(/not correct/i);

    const good = await ok(
      await api("/v1/auth/login", { method: "POST", body: { email: session.email, password: PASSWORD } }),
    );
    expect(good.user.id).toBe(session.userId);
    expect(good.accessToken).toBeTruthy();
  });

  it("rotates refresh tokens so a stolen token dies on first use", async () => {
    const session = await registerUser("auth-refresh");

    const first = await ok(
      await api("/v1/auth/refresh", { method: "POST", body: { refreshToken: session.refreshToken } }),
    );
    expect(first.refreshToken).not.toBe(session.refreshToken);

    // Replaying the token that was just rotated must fail.
    await expectError(
      await api("/v1/auth/refresh", { method: "POST", body: { refreshToken: session.refreshToken } }),
      401,
      "unauthorized",
    );

    // The rotated token works exactly once too.
    const second = await ok(
      await api("/v1/auth/refresh", { method: "POST", body: { refreshToken: first.refreshToken } }),
    );
    expect(second.refreshToken).toBeTruthy();
  });

  it("signs out and invalidates the refresh token", async () => {
    const session = await registerUser("auth-logout");
    await ok(
      await api("/v1/auth/logout", { method: "POST", body: { refreshToken: session.refreshToken } }),
    );
    await expectError(
      await api("/v1/auth/refresh", { method: "POST", body: { refreshToken: session.refreshToken } }),
      401,
      "unauthorized",
    );
  });

  it("requires a bearer token for the personal endpoints", async () => {
    await expectError(await api("/v1/me"), 401, "unauthorized");
    await expectError(await api("/v1/coins/balance"), 401, "unauthorized");

    const session = await registerUser("auth-guard");
    const withToken = await api("/v1/me", { token: session.accessToken });
    expect(withToken.status).toBe(200);
  });

  it("publishes the earning rules on /v1/config", async () => {
    const config = await ok(await api("/v1/config"));
    expect(config.coinEngineVersion).toBe("vcoin-rules-1.0.0");
    expect(config.coinsPerStep).toBe(0.00095);
    expect(config.minStepsForReward).toBe(1000);
    expect(config.maxStepCoinsPerDay).toBe(8);
    expect(config.coinMinClaimGapSeconds).toBe(900);
    expect(config.generativeAiEnabled).toBe(false);
    expect(config.serverTime).toBeGreaterThan(0);
  });

  it("lets a user update and then export their profile", async () => {
    const session = await registerUser("auth-profile");

    const updated = await ok(
      await api("/v1/me", {
        method: "PATCH",
        token: session.accessToken,
        body: { displayName: "Renamed", stepGoal: 12000, fitnessLevel: "INTERMEDIATE", preferredActivities: ["RUNNING"] },
      }),
    );
    expect(updated.displayName).toBe("Renamed");
    expect(updated.stepGoal).toBe(12000);
    expect(updated.fitnessLevel).toBe("INTERMEDIATE");
    expect(updated.preferredActivities).toEqual(["RUNNING"]);

    const exported = await ok(await api("/v1/me/export", { token: session.accessToken }));
    expect(exported.profile.id).toBe(session.userId);
    expect(exported.activity).toEqual([]);
    expect(exported.workouts).toEqual([]);
    expect(exported.transactions).toEqual([]);
    expect(exported.exportedAt).toBeGreaterThan(0);
  });

  it("rejects an out-of-range step goal", async () => {
    const session = await registerUser("auth-range");
    await expectError(
      await api("/v1/me", { method: "PATCH", token: session.accessToken, body: { stepGoal: 500 } }),
      400,
      "validation_error",
    );
  });

  it("deletes the account and every row attached to it", async () => {
    const session = await registerUser("auth-delete");
    const deleted = await api("/v1/me", { method: "DELETE", token: session.accessToken });
    expect(deleted.status).toBe(204);

    await expectError(await api("/v1/me", { token: session.accessToken }), 401, "unauthorized");
  });

  it("returns 404 for an unknown path and 405 for a wrong method", async () => {
    await expectError(await api("/v1/nope"), 404, "not_found");
    await expectError(await api("/v1/coins/balance", { method: "POST" }), 405, "method_not_allowed");
  });
});
