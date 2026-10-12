package com.vtrack.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
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
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.repository.ActivityRepository
import com.vtrack.app.data.repository.CoachRepository
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.data.repository.SyncEngine
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.coach.CoachBriefing
import com.vtrack.app.domain.engine.RewardRules
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.CoinBalance
import com.vtrack.app.domain.model.DailyActivity
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.domain.model.WorkoutSession
import com.vtrack.app.ui.components.BarChart
import com.vtrack.app.ui.components.MetricTile
import com.vtrack.app.ui.components.OfflineBanner
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.ProgressBarThin
import com.vtrack.app.ui.components.ProgressRing
import com.vtrack.app.ui.components.RingCenterValue
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val profile: UserProfile = UserProfile.empty(),
    val today: DailyActivity = DailyActivity.empty(LocalDate.now(), 8_000),
    val week: List<DailyActivity> = emptyList(),
    val streakDays: Int = 0,
    val longestStreak: Int = 0,
    val balance: CoinBalance = CoinBalance(0, 0),
    val todayWorkouts: List<WorkoutSession> = emptyList(),
    val briefing: CoachBriefing? = null,
    val isOnline: Boolean = true,
    val pendingSync: Int = 0,
    val isLoading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val profileRepository: ProfileRepository,
    private val workoutRepository: WorkoutRepository,
    private val rewardsRepository: RewardsRepository,
    private val coachRepository: CoachRepository,
    private val syncEngine: SyncEngine,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val briefing = MutableStateFlow<CoachBriefing?>(null)

    val ui: StateFlow<HomeUiState> = combine(
        profileRepository.observeProfile(),
        activityRepository.observeLastNDays(28),
        rewardsRepository.observeBalance(),
        workoutRepository.observeHistory(50),
        networkMonitor.isOnline,
    ) { profile, last28, balance, workouts, online ->
        val today = last28.lastOrNull()?.takeIf { it.date == LocalDate.now() }
            ?: DailyActivity.empty(LocalDate.now(), profile.stepGoal)
        val week = TimeUtils.lastNDays(7).map { date ->
            last28.firstOrNull { it.date == date } ?: DailyActivity.empty(date, profile.stepGoal)
        }
        HomeUiState(
            profile = profile,
            today = today,
            week = week,
            streakDays = currentStreak(last28),
            longestStreak = longestStreak(last28),
            balance = balance,
            todayWorkouts = workouts.filter {
                it.isFinished && TimeUtils.dayKey(it.startedAt) == TimeUtils.todayKey()
            },
            isOnline = online,
            isLoading = false,
        )
    }
        .combine(briefing) { state, brief -> state.copy(briefing = brief) }
        .combine(syncEngine.observePendingCount()) { state, pending -> state.copy(pendingSync = pending) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { refreshBriefing() }
    }

    fun refreshBriefing() {
        viewModelScope.launch { briefing.value = runCatching { coachRepository.briefing() }.getOrNull() }
    }

    /** Best-effort sync when the dashboard is opened; the worker retries regardless. */
    fun syncNow() {
        viewModelScope.launch { runCatching { syncEngine.sync() } }
    }

    fun startActivity(): ActivityType = ui.value.briefing?.suggestedActivity ?: ActivityType.WALKING

    private fun currentStreak(days: List<DailyActivity>): Int {
        val qualifying = days.filter { it.goalMet || it.activeMinutes >= 20 }.map { it.date }.toHashSet()
        var streak = 0
        var cursor = LocalDate.now()
        if (!qualifying.contains(cursor)) cursor = cursor.minusDays(1)
        while (qualifying.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun longestStreak(days: List<DailyActivity>): Int {
        val dates = days.filter { it.goalMet || it.activeMinutes >= 20 }.map { it.date }.sorted()
        if (dates.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until dates.size) {
            run = if (dates[i] == dates[i - 1].plusDays(1)) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }
}

@Composable
fun HomeScreen(
    onOpenWorkout: () -> Unit,
    onStartActivity: (ActivityType) -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenEarn: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val units = ui.profile.unitSystem

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!ui.isOnline) {
            OfflineBanner(pendingCount = ui.pendingSync)
        }

        /* ---------------- hero: goal ring ---------------- */
        VTrackCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = greeting(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ui.profile.displayName.ifBlank { "Athlete" },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            text = "🔥 ${ui.streakDays} day streak",
                            color = VTrackTheme.colors.streakOrange,
                            icon = Icons.Filled.LocalFireDepartment,
                        )
                    }
                }
                ProgressRing(
                    progress = ui.today.goalProgress,
                    size = 132.dp,
                    strokeWidth = 12.dp,
                ) {
                    RingCenterValue(
                        value = Formatters.steps(ui.today.steps),
                        label = "of ${Formatters.steps(ui.today.goalSteps)}",
                        caption = if (ui.today.goalMet) "Goal reached" else "${(ui.today.goalProgress * 100).toInt()}%",
                    )
                }
            }
            ProgressBarThin(
                progress = ui.today.goalProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        /* ---------------- today's metrics ---------------- */
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricTile(
                label = "Distance",
                value = Formatters.distanceValue(ui.today.distanceMeters, units).let { Formatters.typed(it, 2) },
                unit = Formatters.distanceUnit(units),
                hint = "Estimated from steps",
                accent = VTrackTheme.colors.chartBlue,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Calories",
                value = Formatters.calories(ui.today.calories),
                unit = "kcal",
                hint = "Estimate",
                accent = VTrackTheme.colors.streakOrange,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricTile(
                label = "Active minutes",
                value = ui.today.activeMinutes.toString(),
                unit = "min",
                hint = if (ui.today.activeMinutes >= 30) "Guideline met" else "Aim for 30",
                accent = VTrackTheme.colors.chartTeal,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "V-Coins",
                value = Formatters.compact(ui.balance.confirmed),
                unit = "VC",
                hint = if (ui.balance.pending > 0) {
                    "+${ui.balance.pending} pending"
                } else {
                    "~${ui.balance.estimatedToday} eligible now"
                },
                accent = VTrackTheme.colors.coinGold,
                modifier = Modifier.weight(1f),
            )
        }

        /* ---------------- coach card ---------------- */
        ui.briefing?.let { brief ->
            VTrackCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                accent = MaterialTheme.colorScheme.primary,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(brief.headline, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    brief.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    brief.encouragement,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                StatusChip(
                    text = "RULES-BASED COACH",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        /* ---------------- quick start ---------------- */
        SectionHeader(
            title = "Quick start",
            subtitle = "Sensors + GPS, recorded on device",
            modifier = Modifier.padding(horizontal = 16.dp),
            actionLabel = "Library",
            onAction = onOpenWorkout,
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ActivityType.quickStart.size) { index ->
                val activity = ActivityType.quickStart[index]
                VTrackCard(
                    modifier = Modifier.width(132.dp),
                    onClick = { onStartActivity(activity) },
                ) {
                    Text(activity.emoji, style = MaterialTheme.typography.headlineSmall)
                    Text(activity.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (activity.usesGps) "GPS + sensors" else "Sensors",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PrimaryActionButton(
            text = "Start ${ui.briefing?.suggestedActivity?.label ?: "workout"} now",
            onClick = { onStartActivity(ui.briefing?.suggestedActivity ?: ActivityType.WALKING) },
            modifier = Modifier.padding(horizontal = 16.dp),
            icon = Icons.Filled.DirectionsRun,
        )

        /* ---------------- weekly chart ---------------- */
        VTrackCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionHeader(
                title = "This week",
                subtitle = "Daily steps",
                actionLabel = "Analytics",
                onAction = onOpenAnalytics,
            )
            BarChart(
                values = ui.week.map { it.steps.toFloat() },
                labels = ui.week.map { Formatters.dayInitial(it.date) },
                goal = ui.profile.stepGoal.toFloat(),
                highlightColor = VTrackTheme.colors.chartBlue,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = "BEST ${Formatters.steps(ui.longestStreak)} day streak",
                    color = VTrackTheme.colors.chartPurple,
                )
                StatusChip(
                    text = "GOAL HIT ${ui.week.count { it.goalMet }}/7",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        /* ---------------- today's workouts ---------------- */
        VTrackCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionHeader(
                title = "Today's workouts",
                subtitle = if (ui.todayWorkouts.isEmpty()) "Nothing recorded yet" else "${ui.todayWorkouts.size} session(s)",
                actionLabel = "History",
                onAction = onOpenHistory,
            )
            if (ui.todayWorkouts.isEmpty()) {
                Text(
                    "Start a session from the Workout tab, or log one manually if you trained without your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ui.todayWorkouts.take(3).forEach { session ->
                    WorkoutRow(session = session, units = units)
                }
            }
        }

        /* ---------------- rewards nudge ---------------- */
        VTrackCard(
            modifier = Modifier.padding(horizontal = 16.dp),
            accent = VTrackTheme.colors.coinGold,
            onClick = onOpenEarn,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Savings,
                    contentDescription = null,
                    tint = VTrackTheme.colors.coinGold,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("V-Coin balance: ${Formatters.compact(ui.balance.confirmed)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Rewards cap at ${RewardRules.MAX_STEP_COINS_PER_DAY} coins a day from steps. " +
                            "Coins are virtual credits, not cash.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.Paid, contentDescription = null, tint = VTrackTheme.colors.coinGold)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun WorkoutRow(session: WorkoutSession, units: UnitSystem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(session.activityType.emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(session.activityType.label, style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append(Formatters.duration(session.durationSeconds))
                    if (session.distanceMeters > 50) {
                        append(" • ")
                        append(Formatters.distance(session.distanceMeters, units))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.Analytics,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun greeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..21 -> "Good evening"
        else -> "Late session?"
    }
}
