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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.data.repository.ExerciseRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.Exercise
import com.vtrack.app.domain.model.StrengthSet
import com.vtrack.app.ui.components.MetricTile
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.SecondaryActionButton
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.components.rememberHaptics
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StrengthUiState(
    val sessionId: String = "",
    val exercise: Exercise? = null,
    val exercises: List<Exercise> = emptyList(),
    val sets: List<StrengthSet> = emptyList(),
    val reps: String = "10",
    val weight: String = "",
    val lastPerformance: StrengthSet? = null,
    val personalBestWeight: Double? = null,
    val personalBestReps: Int? = null,
    val restSecondsRemaining: Int = 0,
    val restDuration: Int = 60,
    val elapsedSeconds: Long = 0,
    val finished: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class StrengthSessionViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(StrengthUiState())
    val ui: StateFlow<StrengthUiState> = _ui.asStateFlow()

    private var restJob: Job? = null
    private var timerJob: Job? = null
    private var startedAt: Long = 0

    fun ensureSession(sessionId: String) {
        if (_ui.value.sessionId.isNotEmpty() && _ui.value.sessionId == sessionId) return
        viewModelScope.launch {
            val id = if (sessionId.isNotBlank() && workoutRepository.getSession(sessionId) != null) {
                sessionId
            } else {
                workoutRepository.startSession(ActivityType.STRENGTH, indoor = true).id
            }
            val session = workoutRepository.getSession(id)
            startedAt = session?.startedAt ?: System.currentTimeMillis()
            _ui.update { it.copy(sessionId = id) }
            observeSets(id)
            exerciseRepository.observeAll().collect { list ->
                _ui.update { state ->
                    state.copy(
                        exercises = list,
                        exercise = state.exercise ?: list.firstOrNull { it.category == com.vtrack.app.domain.model.ActivityCategory.STRENGTH },
                    )
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                _ui.update { it.copy(elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000) }
            }
        }
    }

    private fun observeSets(sessionId: String) {
        viewModelScope.launch {
            workoutRepository.observeSets(sessionId).collect { sets ->
                _ui.update { it.copy(sets = sets) }
            }
        }
    }

    fun selectExercise(exercise: Exercise) {
        _ui.update { it.copy(exercise = exercise, reps = (exercise.defaultReps ?: 10).toString(), weight = "") }
        viewModelScope.launch {
            val last = workoutRepository.lastPerformance(exercise.id)
            _ui.update {
                it.copy(
                    lastPerformance = last,
                    personalBestWeight = workoutRepository.bestWeightKg(exercise.id),
                    personalBestReps = workoutRepository.bestReps(exercise.id),
                )
            }
        }
    }

    fun setReps(value: String) = _ui.update { it.copy(reps = value.filter(Char::isDigit).take(3)) }
    fun setWeight(value: String) =
        _ui.update { it.copy(weight = value.filter { c -> c.isDigit() || c == '.' }.take(6)) }

    fun setRestDuration(seconds: Int) = _ui.update { it.copy(restDuration = seconds.coerceIn(15, 300)) }

    fun addSet() {
        val state = _ui.value
        val exercise = state.exercise ?: return
        viewModelScope.launch {
            workoutRepository.addSet(
                sessionId = state.sessionId,
                exerciseId = exercise.id,
                reps = state.reps.toIntOrNull(),
                weightKg = state.weight.toDoubleOrNull(),
                restSeconds = state.restDuration,
                rpe = null,
            )
            _ui.update { it.copy(message = "Set logged", restSecondsRemaining = it.restDuration) }
            startRestTimer()
            _ui.update {
                it.copy(
                    personalBestWeight = maxOf(it.personalBestWeight ?: 0.0, it.weight.toDoubleOrNull() ?: 0.0)
                        .takeIf { value -> value > 0 },
                )
            }
        }
    }

    fun deleteSet(setId: String) {
        viewModelScope.launch { workoutRepository.deleteSet(setId) }
    }

    fun skipRest() {
        restJob?.cancel()
        _ui.update { it.copy(restSecondsRemaining = 0) }
    }

    private fun startRestTimer() {
        restJob?.cancel()
        restJob = viewModelScope.launch {
            var remaining = _ui.value.restDuration
            while (remaining > 0) {
                delay(1_000)
                remaining--
                _ui.update { it.copy(restSecondsRemaining = remaining) }
            }
            _ui.update { it.copy(message = "Rest complete — next set") }
        }
    }

    fun finish(onFinished: (String) -> Unit) {
        val state = _ui.value
        if (state.sessionId.isBlank()) return
        timerJob?.cancel()
        restJob?.cancel()
        viewModelScope.launch {
            workoutRepository.finishSession(state.sessionId)
            _ui.update { it.copy(finished = true) }
            onFinished(state.sessionId)
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }
}

@Composable
fun StrengthSessionScreen(
    sessionId: String,
    onBack: () -> Unit,
    onFinished: (String) -> Unit,
    viewModel: StrengthSessionViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    androidx.compose.runtime.LaunchedEffect(sessionId) {
        viewModel.ensureSession(sessionId)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            VTrackCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Strength session", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Elapsed ${Formatters.duration(ui.elapsedSeconds)} • ${ui.sets.size} sets logged",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(text = "IN PROGRESS", color = VTrackTheme.colors.chartPurple)
                }
                PrimaryActionButton(
                    text = "Finish and save session",
                    onClick = { haptics.impact(); viewModel.finish(onFinished) },
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(
                    title = "Current exercise",
                    subtitle = ui.exercise?.name ?: "Pick one below",
                )
                ui.exercise?.let { exercise ->
                    Text(
                        "${exercise.primaryMuscle} • ${exercise.equipment}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ui.lastPerformance?.let { last ->
                        Text(
                            "Last time: ${last.reps ?: "-"} reps" +
                                (last.weightKg?.let { " @ ${Formatters.typed(it)} kg" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricTile(
                            label = "Best weight",
                            value = ui.personalBestWeight?.let { Formatters.typed(it) } ?: "—",
                            unit = "kg",
                            hint = "Personal record",
                            accent = VTrackTheme.colors.coinGold,
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "Best reps",
                            value = ui.personalBestReps?.toString() ?: "—",
                            hint = "Personal record",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = ui.reps,
                            onValueChange = viewModel::setReps,
                            label = { Text("Reps") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = ui.weight,
                            onValueChange = viewModel::setWeight,
                            label = { Text("Weight kg (optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rest timer: ${ui.restDuration}s", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        listOf(30, 60, 90, 120).forEach { value ->
                            androidx.compose.material3.FilterChip(
                                selected = ui.restDuration == value,
                                onClick = { viewModel.setRestDuration(value) },
                                label = { Text("${value}s") },
                            )
                        }
                    }
                    PrimaryActionButton(text = "Log set", onClick = { haptics.confirm(); viewModel.addSet() })
                    if (ui.restSecondsRemaining > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Rest: ${ui.restSecondsRemaining}s",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            SecondaryActionButton(
                                text = "Skip rest",
                                onClick = viewModel::skipRest,
                                modifier = Modifier.width(140.dp),
                            )
                        }
                    }
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Logged sets", subtitle = "${ui.sets.size} total")
                val mine = ui.sets.filter { it.exerciseId == ui.exercise?.id }
                if (mine.isEmpty()) {
                    Text(
                        "No sets yet for this exercise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    mine.forEach { set ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Set ${set.setIndex + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                "${set.reps ?: "-"} reps" +
                                    (set.weightKg?.let { " @ ${Formatters.typed(it)} kg" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.deleteSet(set.id) }) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Delete set",
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Exercise library", subtitle = "Tap to switch exercise")
                ui.exercises
                    .filter { it.category == com.vtrack.app.domain.model.ActivityCategory.STRENGTH }
                    .take(20)
                    .forEach { exercise ->
                        ExerciseRow(exercise = exercise, onClick = { viewModel.selectExercise(exercise) })
                    }
            }
        }

        item {
            Text(
                "Strength volume and rest are recorded locally first, then synced. Weights are only " +
                    "ever those you type in.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
