package com.vtrack.app.domain.coach

/**
 * Deterministic knowledge base behind the "ask your coach" screen.
 *
 * Design constraints (deliberate, and enforced by tests):
 *  1. Matching is keyword/scoring based — the same question always yields the same answer.
 *  2. Every topic is general fitness guidance grounded in widely published guidelines
 *     (WHO physical activity recommendations, ACSM progression principles). No topic
 *     diagnoses anything and no topic recommends a medical action.
 *  3. Red-flag questions are intercepted by [safetyRedirect] *before* matching, so
 *     symptom-like input never receives a fitness answer.
 */
object FitnessKnowledgeBase {

    data class Topic(
        val id: String,
        val title: String,
        val keywords: List<String>,
        val answer: String,
        val bullets: List<String>,
        val followUps: List<String> = emptyList(),
    )

    val disclaimer = CoachEngine.GENERAL_DISCLAIMER

    /** Symptom / medical red flags. Matched first; never answered with training advice. */
    private val safetyKeywords = listOf(
        "chest pain", "chest tightness", "heart", "palpitation", "faint", "fainted", "passed out",
        "dizzy", "dizziness", "shortness of breath at rest", "can't breathe", "cannot breathe",
        "numbness", "stroke", "injury", "injured", "torn", "fracture", "broken bone",
        "pregnan", "diabet", "blood pressure", "hypertension", "asthma", "epilep",
        "eating disorder", "anorex", "bulimi", "medication", "dosage", "diagnos",
        "sharp pain", "swelling", "concussion", "surgery", "doctor said", "prescription",
    )

    val topics: List<Topic> = listOf(
        Topic(
            id = "step_goal",
            title = "How many steps should I aim for?",
            keywords = listOf("how many steps", "step goal", "10000 steps", "10,000 steps", "daily steps", "steps per day"),
            answer = "There is nothing magic about exactly 10,000 steps — it originated as a marketing number. " +
                "The evidence-based picture is a dose-response curve: meaningful health benefits appear from " +
                "about 6,000–8,000 steps a day for most adults, with additional benefit accumulating up to " +
                "roughly 10,000–12,000, after which the extra return flattens out.",
            bullets = listOf(
                "If you currently average under 5,000 steps, add 500–1,000 per week rather than jumping straight to 10,000.",
                "V-Track raises your target by 500 steps only after you hit your current goal on most days for two weeks.",
                "Steps counted in short walks, stairs and commuting all count — incidental movement adds up.",
                "One or two rest days per week from step chasing is fine; consistency across the month matters more.",
            ),
            followUps = listOf("Build me a walking plan", "Are streaks important?"),
        ),
        Topic(
            id = "cardio_amount",
            title = "How much cardio do I need?",
            keywords = listOf("how much cardio", "cardio per week", "aerobic", "150 minutes", "moderate intensity", "vigorous"),
            answer = "The WHO guideline for adults is 150–300 minutes of moderate-intensity aerobic activity per " +
                "week, or 75–150 minutes of vigorous activity, plus muscle-strengthening work on two or more days.",
            bullets = listOf(
                "Moderate = you can talk but not sing comfortably (roughly 64–76% of max heart rate).",
                "Vigorous = a few words before needing a breath (roughly 77–95% of max).",
                "Time accumulates: three 10-minute brisk walks equal one 30-minute session.",
                "Exceeding 300 minutes a week gives further benefit if you recover well.",
            ),
            followUps = listOf("Should I do HIIT?", "How do I know my intensity?"),
        ),
        Topic(
            id = "strength_frequency",
            title = "How often should I train strength?",
            keywords = listOf("how often strength", "strength training frequency", "lift weights", "weight training", "gym per week", "resistance training"),
            answer = "Two to three full-body sessions per week is the minimum effective dose for general strength " +
                "and muscle maintenance; three to four is where most people see steady progress.",
            bullets = listOf(
                "Leave at least 48 hours before training the same muscle group hard again.",
                "Beginners: 2–3 sets of 8–12 reps at a load where the last 2–3 reps feel genuinely hard (RPE 7–8).",
                "Add load or reps only when you complete all sets with 2+ reps still in reserve.",
                "Two sessions done consistently beat five sessions planned and skipped.",
            ),
            followUps = listOf("How do I progress safely?", "Build me a beginner strength plan"),
        ),
        Topic(
            id = "hiit",
            title = "Should I do HIIT?",
            keywords = listOf("hiit", "interval training", "sprint interval", "tabata", "circuit training"),
            answer = "HIIT is time-efficient and effective, but it is a supplement to steady aerobic work, not a " +
                "replacement. Two high-intensity sessions a week is plenty for most people.",
            bullets = listOf(
                "A common structure: 4×4 minutes hard with 3 minutes easy, or 8–10×30 seconds with 90 seconds easy.",
                "Keep at least 48 hours between hard interval sessions so recovery is not compromised.",
                "Beginners should build 3–4 weeks of base aerobic work before adding intervals.",
                "Stop the interval when form degrades — quality of effort is what drives adaptation.",
            ),
            followUps = listOf("What is the 4x4 interval protocol?", "How much cardio do I need?"),
        ),
        Topic(
            id = "plateau",
            title = "My progress has stalled",
            keywords = listOf("plateau", "stalled", "not progressing", "stuck", "stopped improving", "no progress", "hit a wall"),
            answer = "Plateaus are normal and usually come from one of four places: insufficient overload, " +
                "insufficient recovery, insufficient consistency, or expectations that outrun the timescale.",
            bullets = listOf(
                "Check consistency first: has the last 14-day pattern really matched the plan?",
                "Change one variable at a time — add 5–10% volume, or add load, or add one session per week.",
                "Sleep and stress are training inputs; a week of poor sleep can mask a month of good work.",
                "Zoom out: compare this month to three months ago rather than to last week.",
            ),
            followUps = listOf("How do I progress safely?", "How many rest days do I need?"),
        ),
        Topic(
            id = "dom_soreness",
            title = "Why am I sore after training?",
            keywords = listOf("sore", "soreness", "doms", "muscle ache", "aching", "stiff after", "delayed onset"),
            answer = "Delayed-onset muscle soreness is normal micro-damage and inflammation after unfamiliar or " +
                "harder-than-usual work. It peaks around 24–72 hours and is not a measure of how good a session was.",
            bullets = listOf(
                "Light movement, easy walking and gentle mobility usually help more than complete rest.",
                "Soreness that is sharp, one-sided, or accompanied by swelling is a reason to stop and seek advice.",
                "Progressively increasing volume reduces the size of the soreness response over time.",
                "Aim to train through mild soreness; back off when it changes your movement quality.",
            ),
            followUps = listOf("Should I train when sore?", "What should my warm-up look like?"),
        ),
        Topic(
            id = "rest_days",
            title = "How many rest days do I need?",
            keywords = listOf("rest day", "how many rest", "recovery days", "day off", "overtraining", "overreaching"),
            answer = "Most people do best with one to three easier days a week. Adaptation happens during recovery, " +
                "not during the session itself.",
            bullets = listOf(
                "A recovery day does not have to be inactive — walking, mobility or easy cycling all aid recovery.",
                "Warning signs of doing too much: sleep disruption, resting heart rate creeping up, persistent fatigue, irritability, appetite changes.",
                "Avoid more than three consecutive hard days unless your training history supports it.",
                "V-Track inserts recovery days automatically when it sees three hard days in a row.",
            ),
            followUps = listOf("What is active recovery?", "Why am I sore after training?"),
        ),
        Topic(
            id = "progression",
            title = "How do I progress safely?",
            keywords = listOf("progress", "progressive overload", "increase weight", "how to get stronger", "add weight", "ramp up"),
            answer = "Progression is simply making the same work gradually harder in one controlled dimension at a " +
                "time. The 10% rule (increase weekly volume by no more than ~10%) is a reasonable starting guardrail.",
            bullets = listOf(
                "Strength: add reps first (e.g. 3×8 → 3×12), then load, then sets.",
                "Cardio: add 5–10% to weekly minutes or distance, and hold it for a week before adding more.",
                "Steps: add 500–1,000 to the daily target per week at most.",
                "Deload roughly every 4–6 weeks by cutting volume ~30–40% for one week.",
            ),
            followUps = listOf("My progress has stalled", "How many rest days do I need?"),
        ),
        Topic(
            id = "warmup",
            title = "What should my warm-up look like?",
            keywords = listOf("warm up", "warmup", "stretch before", "dynamic warm up", "cool down", "cooldown"),
            answer = "Warm up for 5–10 minutes with easy movement plus dynamic mobility for the joints you are about " +
                "to load. Save long static stretches for after training or separate sessions.",
            bullets = listOf(
                "Cardio: 5 minutes easy building to your working pace, then 2–3 short accelerations.",
                "Strength: 5 minutes aerobic, then the movement pattern with an empty bar or bodyweight for 2 sets.",
                "Cool down: 3–5 minutes easy movement, then 2–3 minutes of static stretching for what feels tight.",
                "A warm-up should make the first working set feel easier, not tired you out.",
            ),
            followUps = listOf("What is active recovery?", "How do I know my intensity?"),
        ),
        Topic(
            id = "intensity",
            title = "How do I know my intensity?",
            keywords = listOf("intensity", "rpe", "how hard", "heart rate zone", "talk test", "how fast"),
            answer = "Two simple, equipment-free scales work well: the talk test and RPE (rating of perceived " +
                "exertion) from 1 to 10.",
            bullets = listOf(
                "RPE 3–4: easy, could hold a full conversation — warm-up, recovery.",
                "RPE 5–6: moderate, sentences possible but not singing — the base of your weekly cardio.",
                "RPE 7–8: hard, 3–5 words at a time — tempo work, strength sets near the end.",
                "RPE 9–10: very hard, no talking — short intervals only, never the bulk of the week.",
            ),
            followUps = listOf("How much cardio do I need?", "Should I do HIIT?"),
        ),
        Topic(
            id = "weight_management",
            title = "How does activity relate to weight?",
            keywords = listOf("lose weight", "weight loss", "burn fat", "how many calories", "calorie burn", "fat loss"),
            answer = "Activity is one input into energy balance; nutrition is the larger lever for weight change. " +
                "Exercise is, however, extremely effective for maintaining health and for keeping weight off long term.",
            bullets = listOf(
                "A realistic, sustainable rate is roughly 0.25–0.75 kg per week for most adults.",
                "V-Track's calorie figures are estimates with a wide error band; do not treat them as accounting.",
                "Strength training plus adequate protein helps preserve lean mass while weight changes.",
                "Tracking trends over 3–4 weeks is more useful than any single day's number.",
            ),
            followUps = listOf("How many steps should I aim for?", "How much cardio do I need?"),
        ),
        Topic(
            id = "nutrition_basics",
            title = "What should I eat around training?",
            keywords = listOf("what should i eat", "nutrition", "protein", "diet", "meal timing", "pre workout meal", "nutrition basics"),
            answer = "General, non-medical guidance: eat a normal mixed meal 2–3 hours before training, and include " +
                "protein spread across the day. Specific diets and clinical nutrition belong with a dietitian.",
            bullets = listOf(
                "Broadly 1.2–2.0 g of protein per kg of body weight per day supports most active adults.",
                "Carbohydrate before and after longer sessions supports performance and recovery.",
                "Hydrate: pale-yellow urine is a reasonable practical indicator.",
                "No supplement is required for general fitness; food first.",
            ),
            followUps = listOf("How much cardio do I need?", "How many rest days do I need?"),
        ),
        Topic(
            id = "streak_importance",
            title = "Do streaks matter?",
            keywords = listOf("streak", "consistency", "do streaks matter", "losing my streak", "every day"),
            answer = "Streaks are a motivation tool, not a physiological requirement. Consistency across weeks is " +
                "what changes fitness; a missed day is a data point, not a failure.",
            bullets = listOf(
                "Aim for the pattern: 5 of 7 days active is an excellent, sustainable week.",
                "V-Track counts an in-progress day as neutral so a day in progress never breaks a streak.",
                "If a streak ends, restart with a deliberately easy day — momentum beats heroics.",
                "Milestones (3, 7, 14, 30, 100 days) pay a one-off V-Coin bonus.",
            ),
            followUps = listOf("How many steps should I aim for?", "How many rest days do I need?"),
        ),
        Topic(
            id = "beginner_running",
            title = "How do I start running?",
            keywords = listOf("start running", "beginner running", "couch to 5k", "how to run", "run without stopping", "first 5k"),
            answer = "Start with run/walk intervals — they let your joints, tendons and cardiovascular system adapt " +
                "at different speeds, which is exactly what a new runner needs.",
            bullets = listOf(
                "Week 1–2: 20 minutes of 1 minute easy run / 2 minutes walk, three times a week.",
                "Progress the run portion before the total duration.",
                "Most new-runner injuries come from increasing total time or speed too fast, not from running itself.",
                "Cadence around 165–180 steps per minute usually reduces impact per stride.",
            ),
            followUps = listOf("How do I progress safely?", "What should my warm-up look like?"),
        ),
        Topic(
            id = "indoor_vs_outdoor",
            title = "Treadmill vs outdoors",
            keywords = listOf("treadmill vs outdoor", "indoor running", "outdoor running", "which is better", "treadmill incline"),
            answer = "Both work. Outdoor running involves slightly more energy cost from terrain, wind and turns; a " +
                "1% treadmill incline roughly compensates for the lack of air resistance.",
            bullets = listOf(
                "Treadmill is easier to pace precisely and is weather-independent.",
                "Outdoor running builds route variety and is easier to sustain mentally for many people.",
                "GPS distance indoors is unusable — V-Track uses step-derived distance for treadmill sessions.",
                "Mix both if you have access to each; consistency matters more than the surface.",
            ),
        ),
    )

    /** Returns the best-matching topic, or null when nothing scores above the floor. */
    fun match(question: String): Topic? {
        val normalized = question.lowercase().trim()
        if (normalized.isEmpty()) return null
        val scored = topics.map { topic ->
            topic to topic.keywords.sumOf { keyword ->
                when {
                    normalized.contains(keyword) -> 10 + keyword.length / 4
                    keyword.split(" ").all { normalized.contains(it) } -> 6
                    else -> 0
                }
            } + if (normalized.contains(topic.title.lowercase().take(8))) 4 else 0
        }
        val best = scored.maxByOrNull { it.second }
        return best?.takeIf { it.second >= 6 }?.first
    }

    fun safetyRedirect(question: String): CoachAnswer? {
        val normalized = question.lowercase()
        val hit = safetyKeywords.firstOrNull { normalized.contains(it) } ?: return null
        return CoachAnswer(
            question = question,
            topic = "safety",
            answer = "I can't help with that one — questions involving symptoms, existing conditions, " +
                "medication or injuries need a qualified professional who knows your history. V-Track's coach " +
                "only covers general training guidance.",
            bulletPoints = listOf(
                "For chest pain, fainting, severe shortness of breath or sudden weakness, treat it as an emergency and contact local emergency services.",
                "For nagging pain, swelling or an injury that is not settling, see a physiotherapist or doctor.",
                "If you are pregnant or managing a chronic condition, get clearance and a personalised plan from your clinician.",
                "Once you have that guidance, V-Track can help you track whatever plan you are given (detected keyword: \"$hit\").",
            ),
            safetyRedirect = true,
            disclaimer = disclaimer,
        )
    }

    fun fallback(question: String): CoachAnswer = CoachAnswer(
        question = question,
        topic = "general",
        answer = "I don't have a specific answer for that in my knowledge base, but here is how I'd think about it: " +
            "any training question usually resolves into consistency, progression, recovery, or specificity.",
        bulletPoints = listOf(
            "Consistency: are you actually doing the sessions at the frequency you planned?",
            "Progression: has the work got harder in a controlled way over the last 2–4 weeks?",
            "Recovery: are sleep, rest days and fuelling supporting the work you are doing?",
            "Specificity: does the training look like the thing you want to get better at?",
            "Try asking me about steps, cardio, strength frequency, rest days, progression, warm-ups or soreness.",
        ),
        safetyRedirect = false,
        disclaimer = disclaimer,
    )

    /** Topics offered as suggestion chips on the coach screen. */
    fun suggestions(): List<String> = listOf(
        "How many steps should I aim for?",
        "How much cardio do I need?",
        "How often should I train strength?",
        "How many rest days do I need?",
        "How do I progress safely?",
        "Should I do HIIT?",
    )
}
