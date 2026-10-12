package com.vtrack.app.ui.earn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.core.util.TimeUtils
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.domain.model.ChallengePeriod
import com.vtrack.app.domain.model.ChallengeProgress
import com.vtrack.app.ui.components.EmptyPane
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.ProgressBarThin
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
import javax.inject.Inject

data class ChallengesUiState(
    val daily: List<ChallengeProgress> = emptyList(),
    val weekly: List<ChallengeProgress> = emptyList(),
    val claimingId: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ChallengesViewModel @Inject constructor(
    private val rewardsRepository: RewardsRepository,
) : ViewModel() {

    private val claimState = MutableStateFlow<Pair<String?, String?>>(null to null)

    val ui: StateFlow<ChallengesUiState> = combine(
        rewardsRepository.observeChallenges(TimeUtils.todayKey()),
        rewardsRepository.observeChallenges(weekKey()),
        claimState,
    ) { today, week, claim ->
        ChallengesUiState(
            daily = today.filter { it.challenge.period == ChallengePeriod.DAILY },
            weekly = week.filter { it.challenge.period == ChallengePeriod.WEEKLY },
            claimingId = claim.first,
            message = claim.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChallengesUiState())

    init {
        viewModelScope.launch { rewardsRepository.refreshFromServer() }
    }

    fun claim(challengeId: String) {
        claimState.value = challengeId to null
        viewModelScope.launch {
            rewardsRepository.claimChallenge(challengeId).fold(
                onSuccess = { outcome -> claimState.value = null to outcome.message },
                onFailure = { error -> claimState.value = null to error.userMessage() },
            )
        }
    }

    fun clearMessage() {
        claimState.value = null to null
    }

    private fun weekKey(): String {
        val monday = TimeUtils.currentWeek().first()
        return monday.toString()
    }
}

@Composable
fun ChallengesScreen(
    onBack: () -> Unit,
    viewModel: ChallengesViewModel = hiltViewModel(),
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
                Text("Challenges", style = MaterialTheme.typography.headlineSmall)
            }
        }

        ui.message?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.primary) }
        }

        if (ui.daily.isEmpty() && ui.weekly.isEmpty()) {
            item {
                EmptyPane(
                    title = "No challenges available",
                    message = "Challenges are published by the V-Track server. They load when you are " +
                        "online — everything else in the app keeps working meanwhile.",
                )
            }
        }

        if (ui.daily.isNotEmpty()) {
            item { SectionHeader(title = "Today", subtitle = "${ui.daily.size} daily challenges") }
            items(ui.daily.size) { index ->
                ChallengeCard(
                    progress = ui.daily[index],
                    claiming = ui.claimingId == ui.daily[index].challenge.id,
                    onClaim = { viewModel.claim(ui.daily[index].challenge.id) },
                )
            }
        }

        if (ui.weekly.isNotEmpty()) {
            item { SectionHeader(title = "This week", subtitle = "${ui.weekly.size} weekly challenges") }
            items(ui.weekly.size) { index ->
                ChallengeCard(
                    progress = ui.weekly[index],
                    claiming = ui.claimingId == ui.weekly[index].challenge.id,
                    onClaim = { viewModel.claim(ui.weekly[index].challenge.id) },
                )
            }
        }
    }
}

@Composable
private fun ChallengeCard(
    progress: ChallengeProgress,
    claiming: Boolean,
    onClaim: () -> Unit,
) {
    VTrackCard(accent = if (progress.completed && !progress.claimed) VTrackTheme.colors.coinGold else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(progress.challenge.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    progress.challenge.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(text = "+${progress.challenge.rewardCoins} VC", color = VTrackTheme.colors.coinGold)
        }
        ProgressBarThin(progress = progress.fraction)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Formatters.typed(progress.currentValue)} / ${Formatters.typed(progress.challenge.targetValue)} " +
                    progress.challenge.type.metricLabel,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            when {
                progress.claimed -> StatusChip(text = "CLAIMED", color = VTrackTheme.colors.success)
                progress.completed -> PrimaryActionButton(
                    text = "Claim +${progress.challenge.rewardCoins}",
                    onClick = onClaim,
                    loading = claiming,
                    modifier = Modifier.width(180.dp),
                )
                else -> Text(
                    "${(progress.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
