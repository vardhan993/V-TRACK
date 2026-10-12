package com.vtrack.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import com.vtrack.app.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManualLogUiState(
    val activityType: ActivityType = ActivityType.STRENGTH,
    val durationMinutes: String = "45",
    val distanceKm: String = "",
    val steps: String = "",
    val notes: String = "",
    val effort: Int = 6,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val durationSeconds: Long get() = (durationMinutes.toIntOrNull() ?: 0) * 60L
    val valid: Boolean get() = durationSeconds in 60..(24 * 3600)
}

@HiltViewModel
class ManualLogViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ManualLogUiState())
    val ui: StateFlow<ManualLogUiState> = _ui.asStateFlow()

    fun setActivity(type: ActivityType) = _ui.update { it.copy(activityType = type, error = null) }
    fun setDuration(value: String) =
        _ui.update { it.copy(durationMinutes = value.filter(Char::isDigit).take(4), error = null) }

    fun setDistance(value: String) =
        _ui.update { it.copy(distanceKm = value.filter { c -> c.isDigit() || c == '.' }.take(6)) }

    fun setSteps(value: String) =
        _ui.update { it.copy(steps = value.filter(Char::isDigit).take(6)) }

    fun setNotes(value: String) = _ui.update { it.copy(notes = value) }
    fun setEffort(value: Int) = _ui.update { it.copy(effort = value.coerceIn(1, 10)) }

    fun save(onSaved: (String) -> Unit) {
        val state = _ui.value
        if (!state.valid) {
            _ui.update { it.copy(error = "Enter a duration between 1 minute and 24 hours.") }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val startedAt = System.currentTimeMillis() - state.durationSeconds * 1000
                workoutRepository.logManualSession(
                    activityType = state.activityType,
                    startedAt = startedAt,
                    durationSeconds = state.durationSeconds,
                    distanceMeters = (state.distanceKm.toDoubleOrNull() ?: 0.0) * 1000.0,
                    steps = state.steps.toIntOrNull() ?: 0,
                    notes = state.notes.ifBlank { null },
                    perceivedEffort = state.effort,
                    indoor = !state.activityType.usesGps,
                )
            }.fold(
                onSuccess = { session ->
                    _ui.update { it.copy(saving = false) }
                    onSaved(session.id)
                },
                onFailure = { error ->
                    _ui.update { it.copy(saving = false, error = error.userMessage()) }
                },
            )
        }
    }
}

@Composable
fun ManualLogScreen(
    onBack: () -> Unit,
    onLogged: (String) -> Unit,
    viewModel: ManualLogViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

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
                Text("Log a workout", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            VTrackCard(accent = VTrackTheme.colors.warning) {
                Text("Manual entries are labelled as manual", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use this for gym machines, classes or anything your phone cannot measure. Manual " +
                        "sessions count toward your training history; step rewards still require " +
                        "device-verified steps, and a manual entry cannot be claimed twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Activity")
                ActivityType.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { activity ->
                            androidx.compose.material3.FilterChip(
                                selected = ui.activityType == activity,
                                onClick = { viewModel.setActivity(activity) },
                                label = { Text("${activity.emoji} ${activity.label}") },
                            )
                        }
                    }
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Details")
                OutlinedTextField(
                    value = ui.durationMinutes,
                    onValueChange = viewModel::setDuration,
                    label = { Text("Duration (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.activityType.tracksDistance) {
                    OutlinedTextField(
                        value = ui.distanceKm,
                        onValueChange = viewModel::setDistance,
                        label = { Text("Distance (km, optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.activityType.countsSteps) {
                    OutlinedTextField(
                        value = ui.steps,
                        onValueChange = viewModel::setSteps,
                        label = { Text("Steps (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = ui.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Perceived effort: ${ui.effort} / 10", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = ui.effort.toFloat(),
                    onValueChange = { viewModel.setEffort(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(
                        text = "CALORIES ESTIMATED FROM MET",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ui.error?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }

        item {
            PrimaryActionButton(
                text = "Save workout",
                onClick = { viewModel.save(onLogged) },
                enabled = ui.valid,
                loading = ui.saving,
            )
        }
    }
}
