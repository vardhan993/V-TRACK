import { Router } from "../lib/router";
import { jsonResponse } from "../lib/http";
import { registerAdminRoutes } from "./admin";
import { registerActivityRoutes } from "./activity";
import { registerAuthRoutes } from "./auth";
import { registerCoachRoutes } from "./coach";
import { registerMeRoutes } from "./me";
import { registerRewardRoutes } from "./rewards";
import { registerWorkoutRoutes } from "./workouts";

/**
 * Builds the full route table. Order is irrelevant — the router matches on
 * method plus segment count and never supports wildcards, so no route can
 * shadow another.
 */
export function buildRouter(): Router {
  const router = new Router();

  // Unauthenticated liveness probe for the deployment smoke test. Touches no
  // database and returns no data.
  router.get("/health", async () => jsonResponse({ ok: true, service: "vtrack-api" }), { auth: "optional" });

  registerAuthRoutes(router);
  registerMeRoutes(router);
  registerActivityRoutes(router);
  registerWorkoutRoutes(router);
  registerRewardRoutes(router);
  registerCoachRoutes(router);
  registerAdminRoutes(router);
  return router;
}
