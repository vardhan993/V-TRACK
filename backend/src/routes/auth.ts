import { z } from "zod";
import { boolVar, intVar, type Env } from "../env";
import { jsonResponse } from "../lib/http";
import type { Router } from "../lib/router";
import { parseOrThrow, passwordSchema } from "../lib/validate";
import { rulesFromEnv } from "../services/coinRules";
import { login, logout, refresh, register } from "../services/auth";
import { readJson } from "./helpers";

const registerSchema = z.object({
  email: z
    .string()
    .trim()
    .min(3, "Enter your email address")
    .max(254, "That email address is too long")
    .email("Enter a valid email address")
    .transform((value) => value.toLowerCase()),
  password: passwordSchema,
  displayName: z.string().trim().min(1, "Enter a display name").max(80, "That name is too long"),
});

const loginSchema = z.object({
  email: z.string().trim().min(1, "Enter your email address").max(254).transform((value) => value.toLowerCase()),
  password: passwordSchema,
});

const tokenSchema = z.object({
  refreshToken: z.string().min(16, "Missing refresh token").max(400, "Refresh token is too long"),
});

export function registerAuthRoutes(router: Router): void {
  router.post(
    "/v1/auth/register",
    async (ctx) => {
      const input = parseOrThrow(registerSchema, await readJson(ctx));
      const response = await register(ctx.env, input, ctx.now);
      return jsonResponse(response);
    },
    { auth: "optional" },
  );

  router.post(
    "/v1/auth/login",
    async (ctx) => {
      const input = parseOrThrow(loginSchema, await readJson(ctx));
      const response = await login(ctx.env, input, ctx.now);
      return jsonResponse(response);
    },
    { auth: "optional" },
  );

  router.post(
    "/v1/auth/refresh",
    async (ctx) => {
      const input = parseOrThrow(tokenSchema, await readJson(ctx));
      const response = await refresh(ctx.env, input.refreshToken, ctx.now);
      return jsonResponse(response);
    },
    { auth: "optional" },
  );

  router.post(
    "/v1/auth/logout",
    async (ctx) => {
      const input = parseOrThrow(tokenSchema, await readJson(ctx));
      await logout(ctx.env, input.refreshToken, ctx.now);
      return jsonResponse({ ok: true });
    },
    { auth: "optional" },
  );

  router.get(
    "/v1/config",
    async (ctx) => {
      const rules = rulesFromEnv(ctx.env);
      return jsonResponse(serverConfig(ctx.env, rules, ctx.now));
    },
    { auth: "optional" },
  );
}

/** The payload behind `GET /v1/config` — how the app learns the earning rules. */
export function serverConfig(env: Env, rules: ReturnType<typeof rulesFromEnv>, now: number) {
  return {
    coinEngineVersion: rules.engineVersion,
    coinsPerStep: rules.coinsPerStep,
    maxStepCoinsPerDay: rules.maxStepCoinsPerDay,
    minStepsForReward: rules.minStepsForReward,
    maxCreditedStepsPerDay: rules.maxCreditedStepsPerDay,
    coinMinClaimGapSeconds: rules.minClaimGapSeconds,
    generativeAiEnabled: boolVar(env, "GENERATIVE_AI_ENABLED", false),
    aiProvider: env.AI_PROVIDER?.trim() || null,
    minSupportedAppVersion: intVar(env, "MIN_SUPPORTED_APP_VERSION", 1),
    serverTime: now,
  };
}
