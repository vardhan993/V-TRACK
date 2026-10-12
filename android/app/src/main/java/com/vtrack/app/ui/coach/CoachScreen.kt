package com.vtrack.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.data.repository.CoachRepository
import com.vtrack.app.domain.coach.CoachAnswer
import com.vtrack.app.domain.coach.CoachBriefing
import com.vtrack.app.domain.coach.CoachPlan
import com.vtrack.app.domain.coach.CoachSource
import com.vtrack.app.domain.coach.WeeklyReview
import com.vtrack.app.ui.components.LoadingPane
import com.vtrack.app.ui.components.MetricTile
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.SecondaryActionButton
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CoachUiState(
    val loading: Boolean = true,
    val briefing: CoachBriefing? = null,
    val plan: CoachPlan? = null,
    val review: WeeklyReview? = null,
    val suggestions: List<String> = emptyList(),
    val question: String = "",
    val answer: CoachAnswer? = null,
    val asking: Boolean = false,
)

@HiltViewModel
class CoachViewModel @Inject constructor(
    private val coachRepository: CoachRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CoachUiState())
    val ui: StateFlow<CoachUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _ui.update { it.copy(loading = true, suggestions = coachRepository.suggestions()) }
        viewModelScope.launch {
            val briefing = runCatching { coachRepository.briefing() }.getOrNull()
            val plan = runCatching { coachRepository.plan() }.getOrNull()
            val review = runCatching { coachRepository.review() }.getOrNull()
            _ui.update { it.copy(loading = false, briefing = briefing, plan = plan, review = review) }
        }
    }

    fun setQuestion(value: String) = _ui.update { it.copy(question = value) }

    fun ask() {
        val question = _ui.value.question.trim()
        if (question.isEmpty()) return
        _ui.update { it.copy(asking = true) }
        viewModelScope.launch {
            val answer = runCatching { coachRepository.answer(question) }.getOrNull()
            _ui.update { it.copy(asking = false, answer = answer, question = "") }
        }
    }

    fun askSuggested(question: String) {
        _ui.update { it.copy(question = question) }
        ask()
    }

    fun clearAnswer() = _ui.update { it.copy(answer = null) }
}

@Composable
fun CoachScreen(
    onOpenPlan: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: CoachViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("AI Coach", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Deterministic rules engine — not a generative model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.loading) {
            item { LoadingPane(label = "Analysing your recent activity…") }
        }

        ui.briefing?.let { briefing ->
            item {
                VTrackCard(accent = MaterialTheme.colorScheme.primary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(briefing.headline, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(briefing.message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        briefing.encouragement,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            text = if (briefing.isRecoveryDay) "RECOVERY DAY" else "TRAINING DAY",
                            color = if (briefing.isRecoveryDay) {
                                VTrackTheme.colors.chartTeal
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        StatusChip(text = "~${briefing.suggestedMinutes} MIN", color = VTrackTheme.colors.chartBlue)
                    }
                }
            }
        }

        ui.plan?.let { plan ->
            item {
                VTrackCard {
                    SectionHeader(
                        title = "This week's plan",
                        subtitle = plan.headline,
                        actionLabel = "Full plan",
                        onAction = onOpenPlan,
                    )
                    Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricTile(
                            label = "Active minutes",
                            value = plan.weeklyActiveMinutesTarget.toString(),
                            unit = "min",
                            hint = "Weekly target",
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "Step target",
                            value = Formatters.steps(plan.dailyStepTarget),
                            hint = "Per day",
                            accent = VTrackTheme.colors.chartBlue,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            text = plan.generatedBy.label.uppercase(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = Icons.Filled.Info,
                        )
                        StatusChip(text = "v${plan.engineVersion}", color = VTrackTheme.colors.chartPurple)
                    }
                }
            }

            item {
                SectionHeader(title = "Next few days", subtitle = "Generated from your own history")
            }
            items(plan.prescriptions.take(3).size) { index ->
                val prescription = plan.prescriptions[index]
                VTrackCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            prescription.weekdayLabel,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.width(48.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(prescription.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                prescription.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (prescription.isRest) {
                            StatusChip(text = "REST", color = VTrackTheme.colors.chartTeal)
                        } else if (prescription.isRecovery) {
                            StatusChip(text = "RECOVERY", color = VTrackTheme.colors.chartTeal)
                        }
                    }
                }
            }
        }

        ui.review?.let { review ->
            item {
                VTrackCard {
                    SectionHeader(
                        title = "Weekly review",
                        subtitle = "Consistency score ${review.consistencyScore}/100",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricTile(
                            label = "Active minutes",
                            value = "${review.activeMinutes}",
                            unit = "/ ${review.activeMinutesTarget}",
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "Sessions",
                            value = "${review.workoutsCompleted}",
                            unit = "/ ${review.workoutsTarget}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (review.highlights.isNotEmpty()) {
                        Text("What went well", style = MaterialTheme.typography.titleSmall)
                        review.highlights.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (review.watchOuts.isNotEmpty()) {
                        Text("Watch-outs", style = MaterialTheme.typography.titleSmall)
                        review.watchOuts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(
                        "Next week: ${review.nextWeekFocus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Recovery: ${review.recoveryRecommendation}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusChip(
                        text = when (review.adjustment.direction) {
                            com.vtrack.app.domain.coach.PlanAdjustment.Direction.INCREASE ->
                                "PLAN UP ${review.adjustment.percent}%"
                            com.vtrack.app.domain.coach.PlanAdjustment.Direction.REDUCE ->
                                "PLAN DOWN ${review.adjustment.percent}%"
                            else -> "PLAN HELD"
                        },
                        color = VTrackTheme.colors.chartPurple,
                    )
                    Text(
                        review.adjustment.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Ask the coach", subtitle = "General fitness questions only")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.suggestions) { suggestion ->
                        AssistChip(
                            onClick = { viewModel.askSuggested(suggestion) },
                            label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ui.question,
                        onValueChange = viewModel::setQuestion,
                        label = { Text("Your question") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::ask, enabled = ui.question.isNotBlank() && !ui.asking) {
                        Icon(Icons.Filled.Send, contentDescription = "Ask")
                    }
                }
            }
        }

        ui.answer?.let { answer ->
            item {
                VTrackCard(accent = if (answer.safetyRedirect) VTrackTheme.colors.warning else MaterialTheme.colorScheme.primary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (answer.safetyRedirect) "Safety first" else "Coach answer",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusChip(
                            text = answer.topic.uppercase(),
                            color = if (answer.safetyRedirect) {
                                VTrackTheme.colors.warning
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Text(answer.answer, style = MaterialTheme.typography.bodyMedium)
                    answer.bulletPoints.forEach { point ->
                        Text("• $point", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        answer.disclaimer,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryActionButton(text = "Clear", onClick = viewModel::clearAnswer)
                }
            }
        }

        item {
            PrimaryActionButton(text = "Regenerate plan", onClick = viewModel::refresh)
        }

        item {
            Text(
                "This coach is a rules-based system built on published activity guidelines " +
                    "(150–300 moderate minutes a week, ≥2 strength days, ~10% progression guardrails). " +
                    "It is not a generative AI model, does not diagnose anything, and is not a substitute " +
                    "for medical advice.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    LaunchedEffect(ui.plan?.generatedBy) {
        if (ui.plan?.generatedBy == CoachSource.GENERATIVE_MODEL) {
            onMessage("A generative engine is configured on the server; check with the operator before relying on it.")
        }
    }
}
