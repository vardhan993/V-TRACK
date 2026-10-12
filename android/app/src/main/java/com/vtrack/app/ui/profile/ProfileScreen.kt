package com.vtrack.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
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
import com.vtrack.app.data.local.dao.ActivityDao
import com.vtrack.app.data.local.dao.WorkoutDao
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.domain.model.Achievement
import com.vtrack.app.domain.model.CoinBalance
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.ui.components.InlineActionRow
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val profile: UserProfile = UserProfile.empty(),
    val balance: CoinBalance = CoinBalance(0, 0),
    val totalSteps: Int = 0,
    val totalWorkouts: Int = 0,
    val unlockedAchievements: Int = 0,
    val bestDay: Int = 0,
    val achievementList: List<Achievement> = emptyList(),
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    rewardsRepository: RewardsRepository,
    private val activityDao: ActivityDao,
    private val workoutDao: WorkoutDao,
) : ViewModel() {

    private val totals = MutableStateFlow(ProfileUiState())

    val ui: StateFlow<ProfileUiState> = combine(
        profileRepository.observeProfile(),
        rewardsRepository.observeBalance(),
        rewardsRepository.observeAchievements(),
        totals,
    ) { profile, balance, achievements, totalsState ->
        totalsState.copy(
            profile = profile,
            balance = balance,
            unlockedAchievements = achievements.count { it.unlocked },
            achievementList = achievements,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    init {
        viewModelScope.launch {
            val days = activityDao.recentDays(400)
            totals.value = totals.value.copy(
                totalSteps = days.sumOf { it.steps },
                totalWorkouts = workoutDao.observeCompletedWorkoutCount().first(),
                bestDay = activityDao.bestStepDay() ?: 0,
            )
        }
    }
}

@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAchievements: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val profile = ui.profile

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            VTrackCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(profile.avatarEmoji ?: "🏃", style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.displayName.ifBlank { "Athlete" },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            profile.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(text = profile.fitnessLevel.label.uppercase(), color = MaterialTheme.colorScheme.primary)
                            StatusChip(text = profile.unitSystem.name, color = VTrackTheme.colors.chartBlue)
                        }
                    }
                }
                Text(
                    "Goal: ${profile.primaryGoal.label} • ${profile.weeklyWorkoutTarget} sessions/week • " +
                        "${Formatters.steps(profile.stepGoal)} steps/day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profile.bmi?.let { bmi ->
                    Text(
                        "BMI ${Formatters.typed(bmi)} (from the height and weight you provided — a rough " +
                            "indicator, not a diagnosis)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = "Lifetime steps",
                    value = Formatters.compact(ui.totalSteps.toLong()),
                    hint = "Best day ${Formatters.steps(ui.bestDay)}",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Workouts",
                    value = ui.totalWorkouts.toString(),
                    hint = "Completed sessions",
                    accent = VTrackTheme.colors.chartBlue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = "V-Coins",
                    value = Formatters.compact(ui.balance.confirmed),
                    unit = "VC",
                    hint = "Confirmed balance",
                    accent = VTrackTheme.colors.coinGold,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Achievements",
                    value = ui.unlockedAchievements.toString(),
                    hint = "Unlocked",
                    accent = VTrackTheme.colors.chartPurple,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Progress", subtitle = "Your data, your device")
                InlineActionRow(
                    icon = Icons.Filled.Analytics,
                    label = "Analytics & reports",
                    onClick = onOpenAnalytics,
                )
                InlineActionRow(
                    icon = Icons.Filled.History,
                    label = "Workout history",
                    onClick = onOpenHistory,
                )
                InlineActionRow(
                    icon = Icons.Filled.EmojiEvents,
                    label = "Achievements",
                    onClick = onOpenAchievements,
                )
            }
        }

        if (ui.achievementList.count { it.unlocked } > 0) {
            item {
                VTrackCard {
                    SectionHeader(title = "Recent achievements", subtitle = "Keep the streak going")
                    ui.achievementList.filter { it.unlocked }.take(3).forEach { achievement ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(achievement.icon, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(achievement.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    achievement.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            VTrackCard(onClick = onOpenSettings) {
                InlineActionRow(
                    icon = Icons.Filled.Settings,
                    label = "Settings, privacy & account",
                    onClick = onOpenSettings,
                )
            }
        }

        item {
            Text(
                "Your activity is stored locally in an encrypted-backed Room database and only synced " +
                    "to V-Track's own Cloudflare Worker when you are signed in. GPS routes never leave " +
                    "the device unless you explicitly enable it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
