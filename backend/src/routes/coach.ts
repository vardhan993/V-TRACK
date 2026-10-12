import { z } from "zod";
import { ApiError, jsonResponse } from "../lib/http";
import type { Router } from "../lib/router";
import { parseOrThrow } from "../lib/validate";
import { findUserById } from "../services/users";
import { answerQuestion, buildPlan, buildReview, getOrCreatePlan, type PlanInputs } from "../services/coach";
import { dayKeyOf, principalOf, readJson } from "./helpers";

const askSchema = z.object({
  question: z.string().trim().min(2, "Ask a question first").max(600, "That question is too long"),
});

export function registerCoachRoutes(router: Router): void {
  router.get("/v1/coach/plan", async (ctx) => {
    const inputs = await planInputs(ctx);
    const plan = await getOrCreatePlan(ctx.env, inputs, ctx.now);
    return jsonResponse(plan);
  });

  router.get("/v1/coach/review", async (ctx) => {
    const inputs = await planInputs(ctx);
    const plan = buildPlan(inputs);
    return jsonResponse(await buildReview(ctx.db, inputs, plan));
  });

  router.post(
    "/v1/coach/ask",
    async (ctx) => {
      const input = parseOrThrow(askSchema, await readJson(ctx));
      return jsonResponse(answerQuestion(input.question));
    },
    { auth: "optional" },
  );
}

/**
 * The plan and review are built from the caller's stored activity, so the
 * "share activity with coach" switch gates both. `ask` is exempt because the
 * deterministic knowledge base reads no personal data at all.
 */
async function planInputs(ctx: import("../lib/router").Ctx): Promise<PlanInputs> {
  const principal = principalOf(ctx);
  const user = await findUserById(ctx.db, principal.userId);
  if (!user) throw ApiError.unauthorized();
  if (user.share_activity_with_coach !== 1) {
    throw ApiError.forbidden("Turn on 'Share activity with coach' in Settings to get a plan built from your data.");
  }

  return {
    userId: user.id,
    stepGoal: user.step_goal,
    weeklyWorkoutTarget: user.weekly_workout_target,
    fitnessLevel: user.fitness_level,
    primaryGoal: user.primary_goal,
    dayKey: dayKeyOf(ctx),
  };
}
