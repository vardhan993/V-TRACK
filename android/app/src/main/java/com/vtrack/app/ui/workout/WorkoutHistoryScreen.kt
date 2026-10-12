package com.vtrack.app.ui.workout

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
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.domain.model.WorkoutSession
import com.vtrack.app.ui.components.EmptyPane
import com.vtrack.app.ui.components.MetricTile
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class HistoryUiState(
    val sessions: List<WorkoutSession> = emptyList(),
    val profile: UserProfile = UserProfile.empty(),
    val thisWeekMinutes: Int = 0,
    val thisWeekDistanceMeters: Double = 0.0,
    val thisWeekCalories: Double = 0.0,
    val personalRecords: List<String> = emptyList(),
)

@HiltViewModel
class WorkoutHistoryViewModel @Inject constructor(
    workoutRepository: WorkoutRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {

    private val summary = MutableStateFlow(HistoryUiState())

    val ui: StateFlow<HistoryUiState> = combine(
        workoutRepository.observeHistory(300),
        profileRepository.observeProfile(),
        summary,
    ) { sessions, profile, summaryState ->
        summaryState.copy(sessions = sessions, profile = profile)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        viewModelScope.launch {
            val weekStart = TimeUtils.currentWeek().first()
            val from = weekStart.atStartOfDay(TimeUtils.zone()).toInstant().toEpochMilli()
            val to = System.currentTimeMillis()
            val sessions = workoutRepository.sessionsBetween(from, to)
            val records = workoutRepository.personalRecords(6).mapNotNull { record ->
                record.bestWeightKg?.let { "${record.exerciseName} ${Formatters.typed(it)} kg" }
            }
            summary.value = HistoryUiState(
                thisWeekMinutes = (sessions.sumOf { it.durationSeconds } / 60).toInt(),
                thisWeekDistanceMeters = sessions.sumOf { it.distanceMeters },
                thisWeekCalories = sessions.sumOf { it.calories },
                personalRecords = records,
            )
        }
    }
}

@Composable
fun WorkoutHistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    viewModel: WorkoutHistoryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val units = ui.profile.unitSystem

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
                Text("Workout history", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = "This week",
                    value = ui.thisWeekMinutes.toString(),
                    unit = "min",
                    hint = "${ui.sessions.count { it.isFinished }} sessions total",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Distance",
                    value = Formatters.distance(ui.thisWeekDistanceMeters, units),
                    hint = "This week",
                    accent = VTrackTheme.colors.chartBlue,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (ui.personalRecords.isNotEmpty()) {
            item {
                VTrackCard(accent = VTrackTheme.colors.coinGold) {
                    SectionHeader(title = "Personal records", subtitle = "Heaviest recorded sets")
                    ui.personalRecords.forEach { record ->
                        Text("🏅 $record", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (ui.sessions.isEmpty()) {
            item {
                EmptyPane(
                    title = "No workouts yet",
                    message = "Start a session from the Workout tab, or log one manually. Every session " +
                        "you record here is counted from real data.",
                )
            }
        } else {
            item {
                SectionHeader(
                    title = "All sessions",
                    subtitle = "${ui.sessions.size} recorded",
                )
            }
            items(ui.sessions.size) { index ->
                val session = ui.sessions[index]
                val date = Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                VTrackCard(onClick = { onOpenSession(session.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(session.activityType.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.title ?: session.activityType.label,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${Formatters.relativeDay(date)} • ${Formatters.timeOfDay(session.startedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (session.syncState == com.vtrack.app.domain.model.SyncState.PENDING) {
                            StatusChip(text = "LOCAL", color = VTrackTheme.colors.warning)
                        }
                        if (session.manual) {
                            StatusChip(text = "MANUAL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            Formatters.duration(session.durationSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (session.distanceMeters > 50) {
                            Text(
                                Formatters.distance(session.distanceMeters, units),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            "${Formatters.calories(session.calories)} kcal",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (session.avgPaceSecPerKm > 0) {
                            Text(
                                Formatters.pace(session.avgPaceSecPerKm, units),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

