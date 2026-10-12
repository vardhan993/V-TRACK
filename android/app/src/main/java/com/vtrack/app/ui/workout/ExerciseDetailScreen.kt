package com.vtrack.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.TipsAndUpdates
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
import com.vtrack.app.data.repository.ExerciseRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.model.Exercise
import com.vtrack.app.domain.model.StrengthSet
import com.vtrack.app.ui.components.LoadingPane
import com.vtrack.app.ui.components.MetricTile
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

data class ExerciseDetailUiState(
    val exercise: Exercise? = null,
    val history: List<StrengthSet> = emptyList(),
    val bestWeight: Double? = null,
    val bestReps: Int? = null,
)

@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ExerciseDetailUiState())
    val ui: StateFlow<ExerciseDetailUiState> = _ui.asStateFlow()

    fun load(exerciseId: String) {
        if (_ui.value.exercise?.id == exerciseId) return
        viewModelScope.launch {
            val exercise = exerciseRepository.get(exerciseId)
            _ui.value = ExerciseDetailUiState(
                exercise = exercise,
                history = workoutRepository.let { repo ->
                    exercise?.let { repo.setsForExerciseOnce(it.id) } ?: emptyList()
                },
                bestWeight = exercise?.let { workoutRepository.bestWeightKg(it.id) },
                bestReps = exercise?.let { workoutRepository.bestReps(it.id) },
            )
        }
    }
}

@Composable
fun ExerciseDetailScreen(
    exerciseId: String,
    onBack: () -> Unit,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(exerciseId) { viewModel.load(exerciseId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    val exercise = ui.exercise
    if (exercise == null) {
        LoadingPane(label = "Loading exercise…")
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(exercise.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${exercise.primaryMuscle} • ${exercise.equipment}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(text = exercise.category.label.uppercase(), color = MaterialTheme.colorScheme.primary)
            StatusChip(text = exercise.difficulty.label.uppercase(), color = VTrackTheme.colors.chartBlue)
            if (exercise.isCompound) {
                StatusChip(text = "COMPOUND", color = VTrackTheme.colors.chartPurple)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Best weight",
                value = ui.bestWeight?.let { Formatters.typed(it) } ?: "—",
                unit = "kg",
                hint = "Your record",
                accent = VTrackTheme.colors.coinGold,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Best reps",
                value = ui.bestReps?.toString() ?: "—",
                hint = "Your record",
                modifier = Modifier.weight(1f),
            )
        }

        VTrackCard {
            SectionHeader(
                title = "How to do it",
                subtitle = "${exercise.instructions.size} steps",
            )
            exercise.instructions.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (exercise.cues.isNotEmpty()) {
            VTrackCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.TipsAndUpdates,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Technique cues", style = MaterialTheme.typography.titleMedium)
                }
                exercise.cues.forEach { cue ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = VTrackTheme.colors.success,
                            modifier = Modifier.width(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(cue, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (exercise.commonMistakes.isNotEmpty()) {
            VTrackCard(accent = VTrackTheme.colors.warning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = VTrackTheme.colors.warning,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Common mistakes", style = MaterialTheme.typography.titleMedium)
                }
                exercise.commonMistakes.forEach { mistake ->
                    Text("• $mistake", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        VTrackCard {
            SectionHeader(
                title = "Your history",
                subtitle = if (ui.history.isEmpty()) "No sets logged yet" else "${ui.history.size} sets",
            )
            ui.history.take(8).forEach { set ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        com.vtrack.app.core.util.Formatters.dayLabel(
                            java.time.Instant.ofEpochMilli(set.completedAt)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        "${set.reps ?: "-"} reps" +
                            (set.weightKg?.let { " @ ${Formatters.typed(it)} kg" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
