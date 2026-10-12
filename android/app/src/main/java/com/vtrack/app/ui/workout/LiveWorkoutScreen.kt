package com.vtrack.app.ui.workout

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.tracking.LiveWorkoutController
import com.vtrack.app.tracking.LiveWorkoutState
import com.vtrack.app.tracking.LocationTracker
import com.vtrack.app.ui.components.MetricTile
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.SecondaryActionButton
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveUiState(
    val live: LiveWorkoutState = LiveWorkoutState(),
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val distanceIsGps: Boolean = false,
)

@HiltViewModel
class LiveWorkoutViewModel @Inject constructor(
    private val controller: LiveWorkoutController,
    private val profileRepository: ProfileRepository,
    private val workoutRepository: WorkoutRepository,
    private val locationTracker: LocationTracker,
) : ViewModel() {

    private val units = MutableStateFlow(UnitSystem.METRIC)
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var startedForType: ActivityType? = null

    init {
        viewModelScope.launch {
            units.value = profileRepository.currentProfile().unitSystem
        }
        viewModelScope.launch {
            controller.state.collect { live ->
                _state.value = LiveUiState(
                    live = live,
                    unitSystem = units.value,
                    distanceIsGps = !live.indoor && live.gpsActive && live.activityType.usesGps,
                )
            }
        }
        viewModelScope.launch { controller.restoreIfSessionOpen() }
    }

    /** Called by the screen once, so a started session is never duplicated on recompose. */
    fun ensureStarted(activityType: ActivityType, indoor: Boolean) {
        if (startedForType == activityType) return
        if (_state.value.live.isActive) return
        startedForType = activityType
        viewModelScope.launch {
            controller.start(activityType = activityType, indoor = indoor)
        }
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()

    fun stop(onFinished: (String) -> Unit) {
        viewModelScope.launch {
            val session = controller.stop()
            if (session != null) onFinished(session.id)
        }
    }

    fun discard() {
        viewModelScope.launch { controller.discard() }
    }

    fun hasLocationPermission(): Boolean = locationTracker.hasPermission()

    fun saveNotes(sessionId: String, notes: String, effort: Int) {
        viewModelScope.launch { workoutRepository.updateNotes(sessionId, notes, effort) }
    }
}

/**
 * The live workout screen. Values come straight from the tracking controller (hardware
 * steps, GPS distance, elapsed time) — nothing here is simulated.
 */
@Composable
fun LiveWorkoutScreen(
    activityTypeName: String,
    indoor: Boolean,
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: LiveWorkoutViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val live = ui.live
    var confirmDiscard by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf(6) }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    // The activity type travels as a navigation argument, so nothing is guessed. A session
    // that was already open (app killed mid-workout) is restored by the view model instead.
    LaunchedEffect(activityTypeName, live.sessionId) {
        if (live.sessionId == null && activityTypeName.isNotBlank()) {
            viewModel.ensureStarted(ActivityType.fromStorage(activityTypeName), indoor)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${live.activityType.emoji} ${live.activityType.label}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                StatusChip(
                    text = when {
                        live.status == LiveWorkoutState.Status.PAUSED -> "PAUSED"
                        ui.distanceIsGps -> "GPS LOCKED"
                        live.indoor -> "INDOOR — SENSORS"
                        else -> "WAITING FOR GPS"
                    },
                    color = if (live.status == LiveWorkoutState.Status.PAUSED) {
                        VTrackTheme.colors.warning
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            TextButton(onClick = { confirmDiscard = true }) {
                Text("Discard")
            }
        }

        if (!ui.distanceIsGps && !live.indoor && live.activityType.usesGps && !viewModel.hasLocationPermission()) {
            VTrackCard(accent = VTrackTheme.colors.warning) {
                Text("Location permission is needed for a GPS route", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Without it the session still counts steps and calories; distance stays step-derived.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryActionButton(
                    text = "Grant location access",
                    onClick = {
                        locationPermission.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                )
            }
        }

        VTrackCard {
            Text(
                text = Formatters.duration(live.elapsedSeconds),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Elapsed time", style = MaterialTheme.typography.labelMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Distance",
                value = Formatters.typed(Formatters.distanceValue(live.distanceMeters, ui.unitSystem), 2),
                unit = Formatters.distanceUnit(ui.unitSystem),
                hint = if (ui.distanceIsGps) "From GPS" else "From steps",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Steps",
                value = Formatters.steps(live.steps),
                hint = "Hardware counter",
                accent = VTrackTheme.colors.chartBlue,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Pace",
                value = Formatters.pace(live.currentPaceSecPerKm, ui.unitSystem),
                hint = "Last 60s",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Calories",
                value = Formatters.calories(live.calories),
                unit = "kcal",
                hint = "Estimate",
                accent = VTrackTheme.colors.streakOrange,
                modifier = Modifier.weight(1f),
            )
        }

        live.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (live.status == LiveWorkoutState.Status.RUNNING) {
                SecondaryActionButton(
                    text = "Pause",
                    onClick = viewModel::pause,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Pause,
                )
            } else {
                SecondaryActionButton(
                    text = "Resume",
                    onClick = viewModel::resume,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.PlayArrow,
                )
            }
            PrimaryActionButton(
                text = "Finish",
                onClick = { viewModel.stop(onFinished) },
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Flag,
            )
        }

        VTrackCard {
            Text("Notes after you finish", style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("How did it feel?") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Perceived effort: $effort / 10", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Slider(
                value = effort.toFloat(),
                onValueChange = { effort = it.toInt() },
                valueRange = 1f..10f,
                steps = 8,
            )
        }

        Text(
            "Recording stays on this device. Nothing is uploaded until you finish, and never " +
                "without a signed-in account.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this session?") },
            text = { Text("The recording will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.discard()
                        onBack()
                    },
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep recording") }
            },
        )
    }
}
