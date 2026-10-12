package com.vtrack.app.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.repository.ActivityRepository
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.model.DailyActivity
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.ui.components.BarChart
import com.vtrack.app.ui.components.BreakdownBar
import com.vtrack.app.ui.components.ChartLegend
import com.vtrack.app.ui.components.LineChart
import com.vtrack.app.ui.components.MetricTile
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class ReportRange(val label: String, val days: Int) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
    YEAR("365 days", 365),
}

data class AnalyticsUiState(
    val profile: UserProfile = UserProfile.empty(),
    val days: List<DailyActivity> = emptyList(),
    val totalWorkouts: Int = 0,
    val totalWorkoutMinutes: Int = 0,
    val totalWorkoutDistance: Double = 0.0,
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val workoutRepository: WorkoutRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val range = MutableStateFlow(ReportRange.MONTH)
    private val workoutsSummary = MutableStateFlow(Triple(0, 0, 0.0))

    val ui: StateFlow<AnalyticsUiState> = combine(
        profileRepository.observeProfile(),
        activityRepository.observeLastNDays(365),
        range,
        workoutsSummary,
    ) { profile, allDays, selectedRange, summary ->
        val cutoff = LocalDate.now().minusDays(selectedRange.days.toLong())
        AnalyticsUiState(
            profile = profile,
            days = allDays.filter { it.date >= cutoff },
            totalWorkouts = summary.first,
            totalWorkoutMinutes = summary.second,
            totalWorkoutDistance = summary.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    init {
        viewModelScope.launch {
            range.collect { selected ->
                val from = LocalDate.now().minusDays(selected.days.toLong())
                    .atStartOfDay(TimeUtils.zone()).toInstant().toEpochMilli()
                val sessions = workoutRepository.sessionsBetween(from, System.currentTimeMillis())
                workoutsSummary.value = Triple(
                    sessions.size,
                    (sessions.sumOf { it.durationSeconds } / 60).toInt(),
                    sessions.sumOf { it.distanceMeters },
                )
            }
        }
    }

    fun setRange(value: ReportRange) {
        range.value = value
    }
}

@Composable
fun AnalyticsScreen(
    onBack: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf(ReportRange.MONTH) }
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
                Text("Analytics", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportRange.entries.forEach { option ->
                    FilterChip(
                        selected = range == option,
                        onClick = {
                            range = option
                            viewModel.setRange(option)
                        },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = "Total steps",
                    value = Formatters.compact(ui.days.sumOf { it.steps }.toLong()),
                    hint = "${ui.days.size} days recorded",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Average steps",
                    value = if (ui.days.isEmpty()) "0" else {
                        Formatters.steps(ui.days.sumOf { it.steps } / ui.days.size)
                    },
                    hint = "Per recorded day",
                    accent = VTrackTheme.colors.chartBlue,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = "Distance",
                    value = Formatters.distance(ui.days.sumOf { it.distanceMeters }, units),
                    hint = "Step + GPS derived",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Active minutes",
                    value = ui.days.sumOf { it.activeMinutes }.toString(),
                    unit = "min",
                    hint = "Moving time",
                    accent = VTrackTheme.colors.chartTeal,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(
                    title = "Steps trend",
                    subtitle = "Oldest to newest over the selected range",
                )
                LineChart(
                    points = ui.days.map { it.steps.toFloat() },
                    labels = if (ui.days.size > 2) {
                        listOf(
                            Formatters.dayLabel(ui.days.first().date),
                            Formatters.dayLabel(ui.days.last().date),
                        )
                    } else {
                        emptyList()
                    },
                )
                ChartLegend(
                    items = listOf("Steps" to MaterialTheme.colorScheme.primary),
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Goal completion", subtitle = "How often you hit your step goal")
                val hit = ui.days.count { it.goalMet }
                BreakdownBar(
                    segments = listOf(
                        Triple("Goal reached", hit.toFloat(), VTrackTheme.colors.success),
                        Triple("Missed", (ui.days.size - hit).coerceAtLeast(0).toFloat(), VTrackTheme.colors.warning),
                    ),
                )
                Text(
                    "${hit} of ${ui.days.size} days at or above ${Formatters.steps(ui.profile.stepGoal)} steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(
                    title = "Last 7 days",
                    subtitle = "Steps against your daily goal",
                )
                val lastWeek = ui.days.takeLast(7)
                BarChart(
                    values = lastWeek.map { it.steps.toFloat() },
                    labels = lastWeek.map { Formatters.dayInitial(it.date) },
                    goal = ui.profile.stepGoal.toFloat(),
                    highlightColor = VTrackTheme.colors.chartBlue,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Training summary", subtitle = "Sessions in this range")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricTile(
                        label = "Workouts",
                        value = ui.totalWorkouts.toString(),
                        hint = "Completed",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "Time",
                        value = ui.totalWorkoutMinutes.toString(),
                        unit = "min",
                        hint = "Session time",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Distance covered in sessions: ${Formatters.distance(ui.totalWorkoutDistance, units)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Monthly report", subtitle = "Aggregated by calendar month")
                val byMonth = ui.days.groupBy { it.date.month }
                byMonth.entries.sortedBy { it.key.value }.forEach { (month, days) ->
                    val total = days.sumOf { it.steps }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            month.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(
                            "${Formatters.compact(total.toLong())} steps • ${days.sumOf { it.activeMinutes }} min • " +
                                "${Formatters.distance(days.sumOf { it.distanceMeters }, units)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (ui.days.isEmpty()) {
                    Text(
                        "No activity recorded in this range yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Yearly report", subtitle = "Per month totals for the last year")
                val months = TimeUtils.monthsOfYear(LocalDate.now().year)
                BarChart(
                    values = months.map { month ->
                        ui.days.filter { it.date.month == month.month }.sumOf { it.steps }.toFloat()
                    },
                    labels = months.map { it.month.name.take(1) },
                    barColor = VTrackTheme.colors.chartPurple,
                    highlightColor = VTrackTheme.colors.chartTeal,
                )
            }
        }

        item {
            Text(
                "Distance and calories are estimates derived from your steps, GPS and the activity's MET " +
                    "value. Steps themselves come from your device's hardware sensor or Health Connect.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
