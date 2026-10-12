import type { Env } from "../env";
import { addDays, weekStartKey } from "../lib/time";
import { metricsForRange } from "./activity";
import { newId } from "../lib/ids";
import type { D1Database } from "@cloudflare/workers-types";

/**
 * Server-side fitness coach.
 *
 * This is a **deterministic rules engine**, not a generative model. The same
 * inputs always produce the same plan, every recommendation is derived from
 * aggregates the server already stored, and no external AI API is called.
 * `generatedBy` is therefore always `RULES_SERVER`; the optional generative
 * path is gated behind the `GENERATIVE_AI_ENABLED` var and is off by default
 * (see README, "Optional generative AI").
 *
 * Safety: nothing here diagnoses, and symptom-like questions are intercepted
 * before topic matching so they never receive training advice.
 */

export const COACH_ENGINE_VERSION = "coach-rules-1.0.0";
const DISCLAIMER =
  "General fitness information only. V-Track does not provide medical advice, diagnosis or treatment. " +
  "Talk to a qualified professional before changing your exercise routine, especially if you have a medical condition.";

const SAFETY_KEYWORDS = [
  "chest pain", "chest tightness", "heart attack", "palpitation", "faint", "fainted", "passed out",
  "dizzy", "dizziness", "shortness of breath at rest", "can't breathe", "cannot breathe",
  "numbness", "stroke", "injury", "injured", "torn", "fracture", "broken bone",
  "pregnan", "diabet", "blood pressure", "hypertension", "asthma", "epilep",
  "eating disorder", "anorex", "bulimi", "medication", "dosage", "diagnos",
  "sharp pain", "swelling", "concussion", "surgery", "doctor said", "prescription",
];

export function containsSafetyFlag(question: string): boolean {
  const lower = question.toLowerCase();
  return SAFETY_KEYWORDS.some((keyword) => lower.includes(keyword));
}

interface Topic {
  id: string;
  title: string;
  keywords: string[];
  answer: string;
  bullets: string[];
}

const TOPICS: Topic[] = [
  {
    id: "step_goal",
    title: "How many steps should I aim for?",
    keywords: ["how many steps", "step goal", "10000 steps", "10,000 steps", "daily steps", "steps per day"],
    answer:
      "There is nothing magic about exactly 10,000 steps — it started as a marketing number. The evidence is a dose-response curve: meaningful benefit appears from about 6,000–8,000 steps a day for most adults, with more accumulating up to roughly 10,000–12,000 before the curve flattens.",
    bullets: [
      "If you currently average under 5,000, add 500–1,000 per week instead of jumping straight to 10,000.",
      "V-Track only raises your target after you hit the current goal on most days for two weeks.",
      "Short walks, stairs and commuting all count — incidental movement adds up.",
      "One or two easier days a week are fine; consistency across the month matters more than any single day.",
    ],
  },
  {
    id: "cardio_amount",
    title: "How much cardio do I need?",
    keywords: ["how much cardio", "cardio per week", "aerobic", "150 minutes", "moderate intensity", "vigorous"],
    answer:
      "The WHO guideline for adults is 150–300 minutes of moderate-intensity aerobic activity per week, or 75–150 minutes of vigorous activity, plus muscle-strengthening work on two or more days.",
    bullets: [
      "Moderate means you can talk but not sing comfortably.",
      "Vigorous means you can only say a few words before needing a breath.",
      "Time accumulates: three 10-minute brisk walks equal one 30-minute session.",
      "Going beyond 300 minutes can add benefit if you recover well.",
    ],
  },
  {
    id: "strength_frequency",
    title: "How often should I train strength?",
    keywords: ["how often strength", "strength training frequency", "lift weights", "weight training", "gym per week", "resistance training"],
    answer:
      "Two to three full-body sessions a week is the minimum effective dose for general strength; three to four is where most people see steady progress.",
    bullets: [
      "Leave at least 48 hours before training the same muscle group hard again.",
      "Beginners: 2–3 sets of 8–12 reps at a load where the last 2–3 reps feel genuinely hard (RPE 7–8).",
      "Add load or reps only once you finish every set with two or more reps in reserve.",
      "Two sessions done consistently beat five sessions planned and skipped.",
    ],
  },
  {
    id: "hiit",
    title: "Should I do HIIT?",
    keywords: ["hiit", "interval training", "sprint interval", "tabata", "circuit training"],
    answer:
      "HIIT is time-efficient and effective, but it supplements steady aerobic work rather than replacing it. Two high-intensity sessions a week is plenty for most people.",
    bullets: [
      "A common structure is 4×4 minutes hard with 3 minutes easy, or 8–10×30 seconds hard with 90 seconds easy.",
      "Keep at least 48 hours between hard interval sessions.",
      "If your resting heart rate is elevated next morning, take an easy day instead.",
    ],
  },
  {
    id: "progression",
    title: "How do I progress safely?",
    keywords: ["progress", "progressive overload", "get stronger", "plateau", "improve", "advance"],
    answer:
      "Progress one variable at a time and by a small amount — usually 2–10% — only after you can complete the current workload with good technique.",
    bullets: [
      "Add reps first, then load, then volume; never all three in the same week.",
      "Progress lower-body lifts more slowly than upper-body ones.",
      "Deload for a week every 6–8 weeks if you train consistently.",
      "Track your sessions so progression is based on data rather than feel.",
    ],
  },
  {
    id: "recovery",
    title: "How much recovery do I need?",
    keywords: ["recovery", "rest day", "sore", "doms", "overtraining", "tired", "sleep"],
    answer:
      "Recovery is when adaptation happens. Most people do well with one full rest day a week, one or two easy days, and seven to nine hours of sleep.",
    bullets: [
      "Muscle soreness 24–72 hours after a new stimulus is normal; sharp or joint pain is not.",
      "Two hard sessions for the same muscle group in 48 hours usually backfires.",
      "Consistent sleep is the single highest-leverage recovery tool.",
      "An easy week every few weeks keeps progress going longer than grinding through.",
    ],
  },
  {
    id: "weight_loss",
    title: "Can exercise help with weight loss?",
    keywords: ["weight loss", "lose weight", "fat loss", "burn calories", "slimming"],
    answer:
      "Exercise matters most for keeping weight off and for health markers; nutrition drives most of the change on the scale. A modest calorie deficit combined with resistance training protects muscle mass.",
    bullets: [
      "Aim for 150–300 minutes of activity a week rather than crash levels.",
      "Keep protein adequate and strength training regular while in a deficit.",
      "Step count is a useful, honest proxy for daily energy expenditure.",
      "Expect roughly 0.25–0.75 kg per week; faster usually costs muscle.",
    ],
  },
  {
    id: "beginner",
    title: "I'm new to exercise — where do I start?",
    keywords: ["beginner", "start exercising", "new to fitness", "first workout", "getting started", "never exercised"],
    answer:
      "Start with three short sessions a week and a daily step target you can already almost hit. Consistency for four weeks beats an ambitious plan you abandon in one.",
    bullets: [
      "Week 1–2: 20-minute brisk walks plus two full-body bodyweight sessions.",
      "Week 3–4: extend walks to 30 minutes and add one set to each exercise.",
      "Learn the movement patterns before adding load.",
      "Leave at least one rest day between strength sessions.",
    ],
  },
  {
    id: "walking",
    title: "Is walking enough?",
    keywords: ["walking enough", "walk instead of run", "brisk walk", "walking benefits"],
    answer:
      "Yes — walking is a genuinely effective form of moderate activity. Brisk walking at a pace where conversation is possible but singing is not meets the moderate-intensity definition.",
    bullets: [
      "Incline walking and a longer stride raise the intensity without impact.",
      "Splitting it into two 15-minute blocks still counts.",
      "Walking is the best recovery activity on days after hard training.",
    ],
  },
  {
    id: "streaks",
    title: "Are streaks important?",
    keywords: ["streak", "streaks", "consistency", "every day"],
    answer:
      "Streaks are a useful motivational device, not a training principle. The evidence supports regular activity across weeks and months, not never missing a single day.",
    bullets: [
      "An active recovery day still counts as consistency.",
      "A broken streak should not mean a missed week — restart the next day.",
      "V-Track counts a day as active when you meet your step goal or log 20 active minutes.",
    ],
  },
];

export function matchTopic(question: string): { topic: Topic; score: number } | null {
  const lower = question.toLowerCase();
  let best: { topic: Topic; score: number } | null = null;
  for (const topic of TOPICS) {
    let score = 0;
    for (const keyword of topic.keywords) {
      if (lower.includes(keyword)) score += keyword.includes(" ") ? 3 : 2;
    }
    if (score > 0 && (best === null || score > best.score)) best = { topic, score };
  }
  return best;
}

export function answerQuestion(question: string): {
  topic: string;
  answer: string;
  bulletPoints: string[];
  safetyRedirect: boolean;
  disclaimer: string;
} {
  if (containsSafetyFlag(question)) {
    return {
      topic: "safety",
      answer:
        "I can't help with that one. Symptoms and medical questions need a qualified professional, not a fitness app. " +
        "If this is happening right now or it is severe, contact your local emergency number.",
      bulletPoints: [],
      safetyRedirect: true,
      disclaimer: DISCLAIMER,
    };
  }

  const match = matchTopic(question);
  if (!match) {
    return {
      topic: "general",
      answer:
        "I don't have a specific answer for that yet. In general, the highest-return habits are consistent weekly " +
        "activity (150–300 moderate minutes), two strength sessions, and enough sleep. Ask me about steps, cardio, " +
        "strength frequency, HIIT, recovery or progression and I can go deeper.",
      bulletPoints: [
        "Most adults: 150–300 moderate minutes a week plus 2 strength sessions.",
        "Build gradually — about 10% more volume per week at most.",
        "Miss a day, not a week.",
      ],
      safetyRedirect: false,
      disclaimer: DISCLAIMER,
    };
  }

  return {
    topic: match.topic.id,
    answer: match.topic.answer,
    bulletPoints: match.topic.bullets,
    safetyRedirect: false,
    disclaimer: DISCLAIMER,
  };
}

/* ------------------------------------------------------------- plan builder */

export interface CoachDay {
  dayKey: string;
  title: string;
  detail: string;
  activityType: string | null;
  targetMinutes: number;
  targetSteps: number;
  isRecovery: boolean;
  isRest: boolean;
  rationale: string;
}

export interface CoachPlanDto {
  id: string;
  weekStartDayKey: string;
  headline: string;
  summary: string;
  dailyStepTarget: number;
  weeklyActiveMinutesTarget: number;
  weeklyWorkoutTarget: number;
  engineVersion: string;
  generatedBy: string;
  progressions: string[];
  safetyNotes: string[];
  days: CoachDay[];
}

export interface PlanInputs {
  userId: string;
  stepGoal: number;
  weeklyWorkoutTarget: number;
  fitnessLevel: string;
  primaryGoal: string;
  dayKey: string;
}

/** Weekly active-minute target scaled to the user's stated fitness level. */
export function weeklyMinutesTarget(fitnessLevel: string): number {
  switch (fitnessLevel.toUpperCase()) {
    case "BEGINNER":
      return 120;
    case "INTERMEDIATE":
      return 150;
    case "ADVANCED":
      return 210;
    default:
      return 150;
  }
}

const WORKOUT_PATTERN = ["RUNNING", "STRENGTH", null, "CYCLING", "STRENGTH", "HIIT", null];

export function buildPlan(inputs: PlanInputs): CoachPlanDto {
  const weekStart = weekStartKey(inputs.dayKey);
  const workouts = Math.max(1, Math.min(inputs.weeklyWorkoutTarget, 6));
  const dailyStepTarget = Math.max(2_000, Math.min(inputs.stepGoal, 30_000));
  const weeklyMinutes = weeklyMinutesTarget(inputs.fitnessLevel);

  const days: CoachDay[] = [];
  for (let index = 0; index < 7; index++) {
    const dayKey = addDays(weekStart, index);
    const isRest = index === 6;
    const scheduledWorkout = !isRest && index < workouts;
    const isRecovery = !isRest && !scheduledWorkout;

    if (isRest) {
      days.push({
        dayKey,
        title: "Rest day",
        detail: "Full rest, or a gentle 15–20 minute walk if you feel like it.",
        activityType: null,
        targetMinutes: 0,
        targetSteps: Math.round(dailyStepTarget * 0.5),
        isRecovery: false,
        isRest: true,
        rationale: "Adaptation happens on rest days; one per week keeps the week sustainable.",
      });
      continue;
    }

    if (isRecovery) {
      days.push({
        dayKey,
        title: "Recovery day",
        detail: "Easy mobility work or a relaxed walk. Keep effort conversational.",
        activityType: "WALKING",
        targetMinutes: 25,
        targetSteps: Math.round(dailyStepTarget * 0.85),
        isRecovery: true,
        isRest: false,
        rationale: "A deliberately easy day between hard sessions protects the quality of the next one.",
      });
      continue;
    }

    const activityType = WORKOUT_PATTERN[index % WORKOUT_PATTERN.length] ?? "WALKING";
    const strengthDay = activityType === "STRENGTH" || activityType === "HIIT";
    const minutes = strengthDay ? 40 : 45;
    days.push({
      dayKey,
      title: strengthDay ? "Strength session" : "Cardio session",
      detail: strengthDay
        ? "Full-body resistance work: squat, push, pull and a hinge pattern, 2–3 sets each."
        : `${activityType.toLowerCase()} at a steady pace you could hold a conversation through for most of it.`,
      activityType,
      targetMinutes: minutes,
      targetSteps: dailyStepTarget,
      isRecovery: false,
      isRest: false,
      rationale: scheduledWorkout
        ? `Scheduled to hit ${workouts} sessions this week with at least a day between hard efforts.`
        : "",
    });
  }

  const activeTarget = days.reduce((total, day) => total + day.targetMinutes, 0);

  return {
    id: `plan-${weekStart}-${inputs.userId.slice(0, 8)}`,
    weekStartDayKey: weekStart,
    headline: headlineFor(inputs.primaryGoal),
    summary:
      `A ${workouts}-session week built around your ${dailyStepTarget.toLocaleString("en-US")} step target and a ` +
      `${weeklyMinutes}-minute weekly activity goal. Sessions are spaced so no two hard days sit back to back.`,
    dailyStepTarget,
    weeklyActiveMinutesTarget: Math.max(weeklyMinutes, activeTarget),
    weeklyWorkoutTarget: workouts,
    engineVersion: COACH_ENGINE_VERSION,
    generatedBy: "RULES_SERVER",
    progressions: [
      "Add 500 steps to your daily target once you hit the current goal on five of seven days for two consecutive weeks.",
      "Increase lifting load by 2–10% only when every set finishes with two or more reps in reserve.",
      "Add one interval to your cardio session before adding a whole extra session.",
    ],
    safetyNotes: [
      "Stop and seek medical advice for chest pain, dizziness or unusual breathlessness.",
      "Warming up for five minutes before hard work is not optional.",
      "Miss a day rather than training through sharp or joint pain.",
      DISCLAIMER,
    ],
    days,
  };
}

function headlineFor(goal: string): string {
  switch (goal.toUpperCase()) {
    case "LOSE_WEIGHT":
      return "Build the habit, hold the deficit";
    case "BUILD_MUSCLE":
      return "Progressive strength, steady volume";
    case "ENDURANCE":
      return "Aerobic base first, intensity second";
    case "STAY_ACTIVE":
      return "Consistency over intensity";
    default:
      return "A week you can actually complete";
  }
}

export async function getOrCreatePlan(env: Env, inputs: PlanInputs, now: number): Promise<CoachPlanDto> {
  const weekStart = weekStartKey(inputs.dayKey);
  const cached = await env.DB.prepare(
    `SELECT payload_json FROM coach_plans WHERE user_id = ?1 AND week_start_day_key = ?2`,
  )
    .bind(inputs.userId, weekStart)
    .first<{ payload_json: string }>();

  if (cached) {
    try {
      return JSON.parse(cached.payload_json) as CoachPlanDto;
    } catch {
      // Fall through and regenerate.
    }
  }

  const plan = buildPlan(inputs);
  await env.DB.prepare(
    `INSERT INTO coach_plans (id, user_id, week_start_day_key, payload_json, engine_version, generated_by, created_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
     ON CONFLICT(user_id, week_start_day_key) DO UPDATE SET
       payload_json = excluded.payload_json,
       engine_version = excluded.engine_version,
       generated_by = excluded.generated_by,
       created_at = excluded.created_at`,
  )
    .bind(plan.id, inputs.userId, weekStart, JSON.stringify(plan), plan.engineVersion, plan.generatedBy, now)
    .run();

  return plan;
}

/* ------------------------------------------------------------ weekly review */

export interface CoachReviewDto {
  weekStartDayKey: string;
  activeMinutes: number;
  activeMinutesTarget: number;
  workoutsCompleted: number;
  workoutsTarget: number;
  averageSteps: number;
  consistencyScore: number;
  highlights: string[];
  watchOuts: string[];
  nextWeekFocus: string;
  recoveryRecommendation: string;
  adjustmentDirection: "INCREASE" | "HOLD" | "REDUCE";
  adjustmentPercent: number;
  adjustmentReason: string;
}

export async function buildReview(
  db: D1Database,
  inputs: PlanInputs,
  plan: CoachPlanDto,
): Promise<CoachReviewDto> {
  const weekStart = weekStartKey(inputs.dayKey);
  const weekEnd = addDays(weekStart, 6);
  const metrics = await metricsForRange(db, inputs.userId, weekStart, weekEnd);

  // Averages and best day are taken over *every* recorded day in the window,
  // not just the days that happened to meet an activity threshold.
  const range = await db
    .prepare(
      `SELECT COALESCE(SUM(steps), 0) AS steps, COALESCE(SUM(active_minutes), 0) AS minutes,
              COUNT(*) AS days, COALESCE(MAX(steps), 0) AS best
       FROM activity_days WHERE user_id = ?1 AND day_key >= ?2 AND day_key <= ?3`,
    )
    .bind(inputs.userId, weekStart, weekEnd)
    .first<{ steps: number; minutes: number; days: number; best: number }>();

  const activeMinutes = range?.minutes ?? 0;
  const activeTarget = plan.weeklyActiveMinutesTarget;
  const workoutsCompleted = metrics.workoutCount;
  const workoutsTarget = plan.weeklyWorkoutTarget;
  const daysRecorded = Math.max(1, range?.days ?? 1);
  const bestDaySteps = range?.best ?? 0;
  const averageSteps = Math.round((range?.steps ?? 0) / daysRecorded);

  const minutesRatio = activeTarget > 0 ? activeMinutes / activeTarget : 1;
  const workoutRatio = workoutsTarget > 0 ? workoutsCompleted / workoutsTarget : 1;
  const consistencyScore = Math.round(
    Math.max(0, Math.min(100, (minutesRatio * 0.5 + workoutRatio * 0.5) * 100)),
  );

  let direction: CoachReviewDto["adjustmentDirection"] = "HOLD";
  let percent = 0;
  let reason = "";
  if (minutesRatio >= 0.9 && workoutRatio >= 1) {
    direction = "INCREASE";
    percent = 10;
    reason = "You hit both the activity and session targets, so the plan can take a small step up.";
  } else if (minutesRatio < 0.6 || workoutRatio < 0.5) {
    direction = "REDUCE";
    percent = 10;
    reason = "The week landed well under target — trimming the plan makes it achievable again before adding load.";
  } else {
    reason = "You are close to target; holding steady for another week will build consistency first.";
  }

  const highlights: string[] = [];
  if (activeMinutes >= activeTarget) highlights.push(`Logged ${activeMinutes} active minutes against a target of ${activeTarget}.`);
  if (workoutsCompleted >= workoutsTarget) highlights.push(`Completed ${workoutsCompleted} sessions — target met.`);
  if (averageSteps >= inputs.stepGoal) highlights.push(`Averaged ${averageSteps.toLocaleString("en-US")} steps a day, above your goal.`);
  if (bestDaySteps >= 10_000) highlights.push(`Best day: ${bestDaySteps.toLocaleString("en-US")} steps.`);
  if (highlights.length === 0) highlights.push(`${range?.days ?? 0} days with recorded activity — the base is there to build on.`);

  const watchOuts: string[] = [];
  if (workoutsCompleted === 0) watchOuts.push("No completed sessions this week — start with a single 20-minute session.");
  if (averageSteps < inputs.stepGoal * 0.7) watchOuts.push(`Average steps are well below your ${inputs.stepGoal.toLocaleString("en-US")} target.`);
  if (activeMinutes < activeTarget * 0.6) watchOuts.push("Active minutes are under 60% of target; add one walk rather than adding intensity.");
  if (metrics.workoutCount >= 6) watchOuts.push("Six or more sessions in a week — make sure one of them is genuinely easy.");
  if (watchOuts.length === 0) watchOuts.push("Nothing stands out; keep an eye on sleep and soreness as volume rises.");

  return {
    weekStartDayKey: weekStart,
    activeMinutes,
    activeMinutesTarget: activeTarget,
    workoutsCompleted,
    workoutsTarget,
    averageSteps,
    consistencyScore,
    highlights,
    watchOuts,
    nextWeekFocus:
      direction === "INCREASE"
        ? "Add volume gradually — one extra interval or one extra set, not both."
        : direction === "REDUCE"
          ? "Make the week achievable: fewer sessions, all of them completed."
          : "Repeat the structure and focus on completing every scheduled session.",
    recoveryRecommendation:
      metrics.workoutCount >= 5
        ? "Five or more sessions last week — keep the next rest day completely free."
        : "One full rest day and one easy day are enough at this volume.",
    adjustmentDirection: direction,
    adjustmentPercent: percent,
    adjustmentReason: reason,
  };
}

export function newPlanId(): string {
  return `plan-${newId()}`;
}
