package com.vtrack.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.engine.RewardRules
import com.vtrack.app.domain.model.StrengthSet
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.domain.model.WorkoutSession
import com.vtrack.app.ui.components.EmptyPane
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

data class WorkoutDetailUiState(
    val session: WorkoutSession? = null,
    val sets: List<StrengthSet> = emptyList(),
    val profile: UserProfile = UserProfile.empty(),
    val claiming: Boolean = false,
    val claimMessage: String? = null,
    val estimatedCoins: Long = 0,
    val alreadyClaimed: Boolean = false,
    val notFound: Boolean = false,
)

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val rewardsRepository: RewardsRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(WorkoutDetailUiState())
    val ui: StateFlow<WorkoutDetailUiState> = _ui.asStateFlow()

    private var boundSessionId: String? = null

    fun bind(sessionId: String) {
        if (boundSessionId == sessionId) return
        boundSessionId = sessionId
        viewModelScope.launch {
            val session = workoutRepository.getSession(sessionId)
            if (session == null) {
                _ui.update { it.copy(notFound = true) }
                return@launch
            }
            val profile = profileRepository.currentProfile()
            _ui.update {
                it.copy(
                    session = session,
                    profile = profile,
                    sets = workoutRepository.getSets(sessionId),
                    estimatedCoins = RewardRules.coinsForWorkout(
                        session.activityType,
                        session.durationSeconds,
                    ),
                )
            }
            rewardsRepository.observeTransactions(200).collect { transactions ->
                val claimed = transactions.any {
                    it.type == com.vtrack.app.domain.model.CoinTransactionType.WORKOUT_REWARD &&
                        it.activityRef == sessionId &&
                        it.status == com.vtrack.app.domain.model.CoinStatus.CONFIRMED
                }
                _ui.update { it.copy(alreadyClaimed = claimed) }
            }
        }
    }

    fun saveNotes(notes: String, effort: Int) {
        val id = _ui.value.session?.id ?: return
        viewModelScope.launch {
            workoutRepository.updateNotes(id, notes.ifBlank { null }, effort)
            _ui.update { it.copy(claimMessage = "Notes saved") }
        }
    }

    /** Claims the workout reward. The server re-checks duration, caps and duplicates. */
    fun claim() {
        val id = _ui.value.session?.id ?: return
        _ui.update { it.copy(claiming = true, claimMessage = null) }
        viewModelScope.launch {
            rewardsRepository.claimWorkout(id).fold(
                onSuccess = { outcome ->
                    _ui.update { it.copy(claiming = false, claimMessage = outcome.message) }
                },
                onFailure = { error ->
                    _ui.update { it.copy(claiming = false, claimMessage = error.userMessage()) }
                },
            )
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _ui.value.session?.id ?: return
        viewModelScope.launch {
            workoutRepository.deleteSession(id)
            onDeleted()
        }
    }
}

@Composable
fun WorkoutDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: WorkoutDetailViewModel = hiltViewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(sessionId) { viewModel.bind(sessionId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var notes by remember { mutableStateOf("") }
    var effort by remember { mutableIntStateOf(6) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notesInitialised by remember { mutableStateOf(false) }

    val session = ui.session
    if (session == null) {
        if (ui.notFound) {
            EmptyPane(
                title = "Workout not found",
                message = "This session may have been deleted.",
                actionLabel = "Go back",
                onAction = onBack,
            )
        } else {
            com.vtrack.app.ui.components.LoadingPane()
        }
        return
    }

    // Seed the editable fields once from the stored record.
    androidx.compose.runtime.LaunchedEffect(session.id) {
        if (!notesInitialised) {
            notes = session.notes.orEmpty()
            effort = session.perceivedEffort ?: 6
            notesInitialised = true
        }
    }

    val units = ui.profile.unitSystem

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
                Text(
                    session.title ?: session.activityType.label,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "${Formatters.dayLabel(java.time.Instant.ofEpochMilli(session.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate())} • ${Formatters.timeOfDay(session.startedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete workout")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(text = session.activityType.category.label.uppercase(), color = MaterialTheme.colorScheme.primary)
            StatusChip(
                text = if (session.manual) "MANUAL ENTRY" else "SENSOR RECORDED",
                color = if (session.manual) VTrackTheme.colors.warning else VTrackTheme.colors.success,
            )
            if (session.syncState == com.vtrack.app.domain.model.SyncState.PENDING) {
                StatusChip(text = "PENDING SYNC", color = VTrackTheme.colors.warning)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Duration",
                value = Formatters.duration(session.durationSeconds),
                hint = "Active time",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Distance",
                value = Formatters.distance(session.distanceMeters, units),
                hint = if (session.indoor) "Indoor estimate" else "GPS track",
                accent = VTrackTheme.colors.chartBlue,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Calories",
                value = Formatters.calories(session.calories),
                unit = "kcal",
                hint = "MET estimate",
                accent = VTrackTheme.colors.streakOrange,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = if (session.distanceMeters > 50) "Average pace" else "Steps",
                value = if (session.distanceMeters > 50) {
                    Formatters.pace(session.avgPaceSecPerKm, units)
                } else {
                    Formatters.steps(session.steps)
                },
                hint = if (session.distanceMeters > 50) "Per ${Formatters.distanceUnit(units)}" else "Session steps",
                modifier = Modifier.weight(1f),
            )
        }

        if (session.elevationGainMeters > 1) {
            VTrackCard {
                Text(
                    "Elevation gain: ${Formatters.elevation(session.elevationGainMeters, units)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        VTrackCard(accent = VTrackTheme.colors.coinGold) {
            SectionHeader(
                title = "Reward",
                subtitle = "Server-verified V-Coins for this session",
            )
            Text(
                if (ui.alreadyClaimed) {
                    "This workout's reward has already been verified and credited."
                } else {
                    "Eligible: up to ${ui.estimatedCoins} V-Coin(s). The server checks the duration, " +
                        "the daily cap of ${RewardRules.MAX_WORKOUT_REWARDS_PER_DAY} rewarded workouts and " +
                        "duplicate claims before crediting anything."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.claimMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!ui.alreadyClaimed) {
                PrimaryActionButton(
                    text = "Claim verified coins",
                    onClick = viewModel::claim,
                    loading = ui.claiming,
                    icon = Icons.Filled.Paid,
                )
            }
        }

        if (ui.sets.isNotEmpty()) {
            VTrackCard {
                SectionHeader(title = "Sets recorded", subtitle = "${ui.sets.size} sets")
                ui.sets.forEach { set ->
                    Text(
                        "${set.exerciseId.removePrefix("seed.").replace('-', ' ')}: " +
                            "${set.reps ?: "-"} reps" +
                            (set.weightKg?.let { " @ ${Formatters.typed(it)} kg" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        VTrackCard {
            SectionHeader(title = "Notes and effort", subtitle = "Stored with the session")
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("How did it feel?") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Perceived effort: $effort / 10", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = effort.toFloat(),
                onValueChange = { effort = it.toInt() },
                valueRange = 1f..10f,
                steps = 8,
            )
            SecondaryActionButton(
                text = "Save notes",
                onClick = { viewModel.saveNotes(notes, effort) },
            )
        }

        Text(
            if (session.indoor) {
                "Indoor session: distance and calories are estimates derived from your step count and " +
                    "the activity's MET value — no GPS was used."
            } else {
                "Outdoor session: distance comes from the recorded GPS track. The route itself stays " +
                    "on this device unless you explicitly enable route upload in Privacy settings."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this workout?") },
            text = { Text("The session and its sets will be removed locally and from the server copy.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete(onBack)
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
