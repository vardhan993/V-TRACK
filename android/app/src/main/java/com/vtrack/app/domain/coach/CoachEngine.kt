package com.vtrack.app.domain.coach

/**
 * The single coaching seam in V-Track.
 *
 * The shipped implementation is [RulesBasedCoachEngine]: a deterministic, offline,
 * rules-based system built on published activity guidelines. It is *not* a generative
 * model, and the UI labels it accordingly.
 *
 * A genuine AI model (hosted LLM, on-device LLM, partner service) can be added later by
 * implementing this interface — for example [RemoteCoachEngine], which delegates to the
 * Workers API when the user opts in and otherwise falls back to the local rules engine.
 * No screen, view model or use case has to change when that happens.
 */
interface CoachEngine {

    /** Stable identifier persisted with every plan so its origin can be audited. */
    val engineId: String

    /** True only for a real generative model. Drives the honesty label in the UI. */
    val isGenerative: Boolean

    suspend fun weeklyPlan(context: CoachContext): CoachPlan

    suspend fun briefing(context: CoachContext): CoachBriefing

    suspend fun weeklyReview(context: CoachContext): WeeklyReview

    suspend fun answer(question: String, context: CoachContext): CoachAnswer

    companion object {
        /** Attached to every answer so the user is never given medical advice. */
        const val GENERAL_DISCLAIMER: String =
            "This is general fitness information from V-Track's rules-based coach, not medical advice. " +
                "Stop if you feel pain, dizziness or shortness of breath and consult a qualified " +
                "health professional about your own situation."
    }
}
