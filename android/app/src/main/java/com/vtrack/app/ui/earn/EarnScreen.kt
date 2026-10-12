package com.vtrack.app.ui.earn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.repository.ActivityRepository
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.data.repository.SyncEngine
import com.vtrack.app.domain.engine.RewardRules
import com.vtrack.app.domain.model.Achievement
import com.vtrack.app.domain.model.ChallengeProgress
import com.vtrack.app.domain.model.CoinBalance
import com.vtrack.app.domain.model.DailyActivity
import com.vtrack.app.ui.components.MetricTile
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.ProgressBarThin
import com.vtrack.app.ui.components.SecondaryActionButton
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
import java.time.LocalDate
import javax.inject.Inject

data class EarnUiState(
    val balance: CoinBalance = CoinBalance(0, 0),
    val today: DailyActivity = DailyActivity.empty(LocalDate.now(), 8_000),
    val challenges: List<ChallengeProgress> = emptyList(),
    val achievements: List<Achievement> = emptyList(),
    val claiming: Boolean = false,
    val message: String? = null,
    val nextStreakMilestone: Pair<Int, Long>? = null,
)

@HiltViewModel
class EarnViewModel @Inject constructor(
    private val rewardsRepository: RewardsRepository,
    activityRepository: ActivityRepository,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    private val claimState = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    val ui: StateFlow<EarnUiState> = combine(
        rewardsRepository.observeBalance(),
        activityRepository.observeToday(),
        rewardsRepository.observeChallenges(),
        rewardsRepository.observeAchievements(),
        claimState,
    ) { balance, today, challenges, achievements, claim ->
        EarnUiState(
            balance = balance,
            today = today,
            challenges = challenges,
            achievements = achievements.filter { it.unlocked }.take(4),
            claiming = claim.first,
            message = claim.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EarnUiState())

    init {
        viewModelScope.launch { rewardsRepository.refreshFromServer() }
    }

    /** Submits today's cumulative step count; the server credits only new steps. */
    fun claimSteps() {
        claimState.value = true to null
        viewModelScope.launch {
            val result = rewardsRepository.claimStepsToday()
            result.fold(
                onSuccess = { outcome ->
                    claimState.value = false to outcome.message
                    syncEngine.sync()
                },
                onFailure = { error -> claimState.value = false to error.userMessage() },
            )
        }
    }

    fun claimChallenge(challengeId: String) {
        claimState.value = true to null
        viewModelScope.launch {
            rewardsRepository.claimChallenge(challengeId).fold(
                onSuccess = { outcome -> claimState.value = false to outcome.message },
                onFailure = { error -> claimState.value = false to error.userMessage() },
            )
        }
    }

    fun clearMessage() {
        claimState.value = false to null
    }
}

@Composable
fun EarnScreen(
    onOpenWallet: () -> Unit,
    onOpenChallenges: () -> Unit,
    onOpenRewards: () -> Unit,
    onOpenAchievements: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: EarnViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.message) {
        ui.message?.let {
            onMessage(it)
            viewModel.clearMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            VTrackCard(accent = VTrackTheme.colors.coinGold) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Paid, contentDescription = null, tint = VTrackTheme.colors.coinGold)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("V-Coin balance", style = MaterialTheme.typography.labelMedium)
                        Text(
                            Formatters.compact(ui.balance.confirmed),
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                    StatusChip(
                        text = "${ui.balance.pending} PENDING",
                        color = if (ui.balance.pending > 0) {
                            VTrackTheme.colors.warning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    "Virtual credits inside V-Track. They have no cash value and cannot be withdrawn — " +
                        "they are redeemed for in-app perks and partner offers listed in the catalogue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(
                    title = "Today's step reward",
                    subtitle = "${Formatters.steps(ui.today.steps)} of " +
                        "${RewardRules.MAX_CREDITED_STEPS_PER_DAY} creditable steps",
                )
                MetricTile(
                    label = "Estimated from this device",
                    value = ui.balance.estimatedToday.toString(),
                    unit = "VC",
                    hint = "Unverified until the server confirms",
                    accent = VTrackTheme.colors.coinGold,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Rewards start at ${Formatters.steps(RewardRules.MIN_STEPS_FOR_REWARD)} steps and cap at " +
                        "${RewardRules.MAX_STEP_COINS_PER_DAY} coins a day. Steps beyond " +
                        "${Formatters.steps(RewardRules.MAX_CREDITED_STEPS_PER_DAY)} still count for your " +
                        "goals but do not earn coins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryActionButton(
                    text = "Claim verified coins",
                    onClick = viewModel::claimSteps,
                    loading = ui.claiming,
                    enabled = ui.today.steps >= RewardRules.MIN_STEPS_FOR_REWARD,
                )
                SecondaryActionButton(
                    text = "Open wallet & history",
                    onClick = onOpenWallet,
                )
            }
        }

        item {
            SectionHeader(
                title = "Challenges",
                subtitle = "Daily and weekly targets with coin bonuses",
                actionLabel = "All",
                onAction = onOpenChallenges,
            )
        }

        items(ui.challenges.size) { index ->
            val progress = ui.challenges[index]
            VTrackCard(
                onClick = onOpenChallenges,
                accent = if (progress.completed && !progress.claimed) VTrackTheme.colors.coinGold else null,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(progress.challenge.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            progress.challenge.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(
                        text = "+${progress.challenge.rewardCoins} VC",
                        color = VTrackTheme.colors.coinGold,
                    )
                }
                ProgressBarThin(progress = progress.fraction)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Formatters.typed(progress.currentValue)} / ${Formatters.typed(progress.challenge.targetValue)} " +
                            progress.challenge.type.metricLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    if (progress.claimed) {
                        StatusChip(text = "CLAIMED", color = VTrackTheme.colors.success)
                    } else if (progress.completed) {
                        PrimaryActionButton(
                            text = "Claim",
                            onClick = { viewModel.claimChallenge(progress.challenge.id) },
                            modifier = Modifier.width(120.dp),
                        )
                    }
                }
            }
        }

        item {
            VTrackCard(
                onClick = onOpenRewards,
                accent = VTrackTheme.colors.chartPurple,
            ) {
                SectionHeader(
                    title = "Reward catalogue",
                    subtitle = "Redeem coins for perks",
                    actionLabel = "Browse",
                    onAction = onOpenRewards,
                )
                Text(
                    "Redemptions are server-side: the balance is debited atomically and the request is " +
                        "tracked through requested → approved → fulfilled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard(onClick = onOpenAchievements) {
                SectionHeader(
                    title = "Achievements",
                    subtitle = "${ui.achievements.size} unlocked recently",
                    actionLabel = "All",
                    onAction = onOpenAchievements,
                )
                if (ui.achievements.isEmpty()) {
                    Text(
                        "No achievements unlocked yet — your first 10,000-step day is the usual opener.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ui.achievements.forEach { achievement ->
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
            VTrackCard {
                SectionHeader(title = "How earning works", subtitle = "The honest version")
                Text(
                    "• Step rewards are calculated from the steps your device recorded, submitted as a " +
                        "cumulative daily total. Re-sending the same total earns nothing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Workout rewards need a real session of at least " +
                        "${RewardRules.MIN_WORKOUT_SECONDS_FOR_REWARD / 60} minutes, and cap at " +
                        "${RewardRules.MAX_WORKOUT_REWARDS_PER_DAY} per day.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• The app shows an estimate; only the server's confirmed ledger changes your balance.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Engine version ${RewardRules.ENGINE_VERSION} is mirrored on the server so the two " +
                        "cannot drift apart silently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Today: ${Formatters.dayLabel(LocalDate.now())} • day key ${TimeUtils.todayKey()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
