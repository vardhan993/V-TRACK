package com.vtrack.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.vtrack.app.domain.coach.CoachPlan
import com.vtrack.app.ui.components.EmptyPane
import com.vtrack.app.ui.components.LoadingPane
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlanUiState(
    val loading: Boolean = true,
    val plan: CoachPlan? = null,
    val regenerating: Boolean = false,
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val coachRepository: CoachRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlanUiState())
    val ui: StateFlow<PlanUiState> = _ui.asStateFlow()

    init {
        load()
    }

    fun load(refresh: Boolean = false) {
        _ui.value = _ui.value.copy(loading = !refresh, regenerating = refresh)
        viewModelScope.launch {
            val plan = runCatching { coachRepository.plan(forceRefresh = refresh) }.getOrNull()
            _ui.value = PlanUiState(loading = false, plan = plan, regenerating = false)
        }
    }
}

@Composable
fun PlanScreen(
    onBack: () -> Unit,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    if (ui.loading) {
        LoadingPane(label = "Building your week…")
        return
    }

    val plan = ui.plan
    if (plan == null) {
        EmptyPane(
            title = "No plan available",
            message = "Log a little activity and the coach will build a plan from it.",
            actionLabel = "Try again",
            onAction = { viewModel.load(refresh = true) },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Your week", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Week of ${Formatters.dayLabel(plan.weekStart)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            VTrackCard(accent = MaterialTheme.colorScheme.primary) {
                Text(plan.headline, style = MaterialTheme.typography.titleLarge)
                Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(text = plan.generatedBy.label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusChip(text = "ENGINE ${plan.engineVersion}", color = VTrackTheme.colors.chartPurple)
                }
            }
        }

        items(plan.prescriptions.size) { index ->
            val day = plan.prescriptions[index]
            VTrackCard(accent = if (day.isRest) VTrackTheme.colors.chartTeal else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        day.weekdayLabel,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(52.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(day.title, style = MaterialTheme.typography.titleSmall)
                        if (day.targetMinutes > 0) {
                            Text(
                                "${day.targetMinutes} min • step target ${Formatters.steps(day.targetSteps)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    day.activityType?.let {
                        Text(it.emoji, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(day.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Why: ${day.rationale}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (plan.progressions.isNotEmpty()) {
            item {
                VTrackCard {
                    SectionHeader(title = "Why the plan changed", subtitle = "Transparent adjustments")
                    plan.progressions.forEach { note ->
                        Text("• $note", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            VTrackCard(accent = VTrackTheme.colors.warning) {
                SectionHeader(title = "Safety", subtitle = "Always reads before you train")
                plan.safetyNotes.forEach { note ->
                    Text("• $note", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            PrimaryActionButton(
                text = "Regenerate from latest data",
                onClick = { viewModel.load(refresh = true) },
                loading = ui.regenerating,
            )
        }
    }
}
