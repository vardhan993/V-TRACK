package com.vtrack.app.ui.earn

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.domain.model.CoinBalance
import com.vtrack.app.domain.model.Redemption
import com.vtrack.app.domain.model.Reward
import com.vtrack.app.ui.components.EmptyPane
import com.vtrack.app.ui.components.PrimaryActionButton
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

data class RewardsUiState(
    val rewards: List<Reward> = emptyList(),
    val redemptions: List<Redemption> = emptyList(),
    val balance: CoinBalance = CoinBalance(0, 0),
    val redeeming: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RewardsViewModel @Inject constructor(
    private val rewardsRepository: RewardsRepository,
) : ViewModel() {

    private val state = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    val ui: StateFlow<RewardsUiState> = combine(
        rewardsRepository.observeRewards(),
        rewardsRepository.observeRedemptions(),
        rewardsRepository.observeBalance(),
        state,
    ) { rewards, redemptions, balance, claim ->
        RewardsUiState(
            rewards = rewards,
            redemptions = redemptions,
            balance = balance,
            redeeming = claim.first,
            message = claim.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RewardsUiState())

    init {
        viewModelScope.launch { rewardsRepository.refreshFromServer() }
    }

    fun redeem(reward: Reward) {
        state.value = true to null
        viewModelScope.launch {
            rewardsRepository.redeem(reward.id).fold(
                onSuccess = { redemption ->
                    state.value = false to "Requested ${redemption.rewardTitle} — the operator will action it."
                },
                onFailure = { error -> state.value = false to error.userMessage() },
            )
        }
    }

    fun clearMessage() {
        state.value = false to null
    }
}

@Composable
fun RewardsScreen(
    onBack: () -> Unit,
    viewModel: RewardsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Reward?>(null) }

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
                Column(Modifier.weight(1f)) {
                    Text("Rewards", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Balance ${Formatters.compact(ui.balance.confirmed)} VC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ui.message?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary)
                LaunchedEffect(message) { viewModel.clearMessage() }
            }
        }

        if (ui.rewards.isEmpty()) {
            item {
                EmptyPane(
                    title = "Catalogue unavailable",
                    message = "The reward catalogue is published by the V-Track server. Connect once to " +
                        "load it — the rest of the app works offline.",
                )
            }
        }

        items(ui.rewards.size) { index ->
            val reward = ui.rewards[index]
            val affordable = ui.balance.confirmed >= reward.costCoins
            VTrackCard(accent = if (affordable) VTrackTheme.colors.coinGold else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(reward.emoji ?: "🎁", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(reward.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            reward.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        reward.fulfillmentNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    StatusChip(text = "${reward.costCoins} VC", color = VTrackTheme.colors.coinGold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (reward.stock != null) "${reward.stock} remaining" else reward.category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    PrimaryActionButton(
                        text = if (affordable) "Redeem" else "Need ${reward.costCoins - ui.balance.confirmed} more",
                        onClick = { pending = reward },
                        enabled = affordable,
                        modifier = Modifier.width(200.dp),
                    )
                }
            }
        }

        if (ui.redemptions.isNotEmpty()) {
            item {
                SectionHeader(title = "Your requests", subtitle = "${ui.redemptions.size} tracked")
            }
            items(ui.redemptions.size) { index ->
                val redemption = ui.redemptions[index]
                VTrackCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(redemption.rewardTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${redemption.costCoins} VC • requested " +
                                    Formatters.dayLabel(
                                        java.time.Instant.ofEpochMilli(redemption.requestedAt)
                                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusChip(
                            text = redemption.status.label.uppercase(),
                            color = when (redemption.status) {
                                com.vtrack.app.domain.model.RedemptionStatus.FULFILLED -> VTrackTheme.colors.success
                                com.vtrack.app.domain.model.RedemptionStatus.REJECTED -> MaterialTheme.colorScheme.error
                                else -> VTrackTheme.colors.warning
                            },
                        )
                    }
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "How redemption works", subtitle = "Server-side and reversible only by the operator")
                Text(
                    "A redemption debits your confirmed balance inside a single database transaction, so it " +
                        "can never overdraw. This build ships a catalogue of in-app perks and partner-style " +
                        "offers; there is no cash-out and no promised monetary value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pending?.let { reward ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Redeem ${reward.title}?") },
            text = {
                Text(
                    "${reward.costCoins} V-Coins will be debited from your confirmed balance. " +
                        "Requests are reviewed by the operator and can be cancelled while pending.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.redeem(reward)
                        pending = null
                    },
                ) { Text("Request") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
