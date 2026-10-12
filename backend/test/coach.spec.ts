import { describe, expect, it } from "vitest";
import { addDaysKey, activityDay, api, dayKey, expectError, ok, registerUser, weekStartOf } from "./helpers";

describe("coach", () => {
  it("builds a seven-day plan from the caller's profile", async () => {
    const session = await registerUser("coach-plan");
    const plan = await ok(await api("/v1/coach/plan", { token: session.accessToken }));

    expect(plan.generatedBy).toBe("RULES_SERVER");
    expect(plan.engineVersion).toBe("coach-rules-1.0.0");
    expect(plan.weekStartDayKey).toHaveLength(10);
    expect(plan.dailyStepTarget).toBe(8000);
    expect(plan.weeklyWorkoutTarget).toBe(3);
    expect(plan.days).toHaveLength(7);
    expect(plan.headline.length).toBeGreaterThan(0);
    expect(plan.progressions.length).toBeGreaterThan(0);
    expect(plan.safetyNotes.join(" ")).toMatch(/medical/i);

    const restDays = plan.days.filter((day: any) => day.isRest);
    expect(restDays).toHaveLength(1);
    expect(restDays[0].dayKey).toBe(addDaysKey(plan.weekStartDayKey, 6));

    const workoutDays = plan.days.filter((day: any) => !day.isRest && !day.isRecovery);
    expect(workoutDays).toHaveLength(3);

    // The plan is cached: a second call returns the identical week.
    const again = await ok(await api("/v1/coach/plan", { token: session.accessToken }));
    expect(again.id).toBe(plan.id);
  });

  it("scores the completed week and recommends an adjustment", async () => {
    const session = await registerUser("coach-review");
    // Fixture days are pinned to the *current week* so the assertions hold no
    // matter which weekday the suite happens to run on.
    const weekStart = weekStartOf(dayKey());

    await ok(
      await api("/v1/activity/sync", {
        method: "POST",
        token: session.accessToken,
        body: {
          days: [
            activityDay(weekStart, 10_000, 45),
            activityDay(addDaysKey(weekStart, 1), 9_000, 40),
            activityDay(addDaysKey(weekStart, 2), 11_000, 50),
          ],
        },
      }),
    );

    const review = await ok(await api("/v1/coach/review", { token: session.accessToken }));
    expect(review.weekStartDayKey).toBe(weekStart);
    expect(review.activeMinutes).toBe(135);
    expect(review.averageSteps).toBe(10_000);
    expect(review.workoutsCompleted).toBe(0);
    expect(review.consistencyScore).toBeGreaterThanOrEqual(0);
    expect(review.consistencyScore).toBeLessThanOrEqual(100);
    expect(["INCREASE", "HOLD", "REDUCE"]).toContain(review.adjustmentDirection);
    expect(review.adjustmentReason.length).toBeGreaterThan(0);
    expect(review.highlights.length).toBeGreaterThan(0);
    expect(review.nextWeekFocus.length).toBeGreaterThan(0);
    expect(review.recoveryRecommendation.length).toBeGreaterThan(0);
  });

  it("answers general questions deterministically", async () => {
    const first = await ok(
      await api("/v1/coach/ask", { method: "POST", body: { question: "How many steps should I aim for?" } }),
    );
    expect(first.topic).toBe("step_goal");
    expect(first.safetyRedirect).toBe(false);
    expect(first.bulletPoints.length).toBeGreaterThan(0);
    expect(first.disclaimer).toMatch(/not provide medical advice/i);

    const second = await ok(
      await api("/v1/coach/ask", { method: "POST", body: { question: "How many steps should I aim for?" } }),
    );
    expect(second).toEqual(first);
  });

  it("intercepts symptom questions before matching a topic", async () => {
    const answer = await ok(
      await api("/v1/coach/ask", {
        method: "POST",
        body: { question: "I get chest pain when I run, what should I do?" },
      }),
    );
    expect(answer.safetyRedirect).toBe(true);
    expect(answer.topic).toBe("safety");
    expect(answer.bulletPoints).toEqual([]);
    expect(answer.answer).toMatch(/qualified professional/i);
  });

  it("falls back to generic guidance when nothing matches", async () => {
    const answer = await ok(
      await api("/v1/coach/ask", { method: "POST", body: { question: "What is the meaning of life?" } }),
    );
    expect(answer.topic).toBe("general");
    expect(answer.safetyRedirect).toBe(false);
  });

  it("gates plan and review behind the activity-sharing switch", async () => {
    const session = await registerUser("coach-gate");
    await ok(
      await api("/v1/me", {
        method: "PATCH",
        token: session.accessToken,
        body: { shareActivityWithCoach: false },
      }),
    );

    await expectError(await api("/v1/coach/plan", { token: session.accessToken }), 403, "forbidden");
    await expectError(await api("/v1/coach/review", { token: session.accessToken }), 403, "forbidden");

    // `ask` reads no personal data, so it stays available.
    const answer = await ok(
      await api("/v1/coach/ask", { method: "POST", body: { question: "Should I do HIIT?" } }),
    );
    expect(answer.topic).toBe("hiit");
  });

  it("validates the question length", async () => {
    await expectError(
      await api("/v1/coach/ask", { method: "POST", body: { question: "x" } }),
      400,
      "validation_error",
    );
    await expectError(
      await api("/v1/coach/ask", { method: "POST", body: { question: "a".repeat(700) } }),
      400,
      "validation_error",
    );
  });
});
