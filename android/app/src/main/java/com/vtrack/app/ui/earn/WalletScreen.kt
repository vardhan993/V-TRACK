package com.vtrack.app.ui.earn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.domain.model.CoinBalance
import com.vtrack.app.domain.model.CoinStatus
import com.vtrack.app.domain.model.CoinTransaction
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class WalletUiState(
    val balance: CoinBalance = CoinBalance(0, 0),
    val transactions: List<CoinTransaction> = emptyList(),
    val lifetimeEarned: Long = 0,
    val lifetimeSpent: Long = 0,
    val refundedCount: Int = 0,
)

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val rewardsRepository: RewardsRepository,
) : ViewModel() {

    private val deniedCount = MutableStateFlow(0)

    private val lifetime = MutableStateFlow(Triple(0L, 0L, 0))

    val ui: StateFlow<WalletUiState> = combine(
        rewardsRepository.observeBalance(),
        rewardsRepository.observeTransactions(200),
        lifetime,
    ) { balance, transactions, totals ->
        WalletUiState(
            balance = balance,
            transactions = transactions,
            lifetimeEarned = totals.first,
            lifetimeSpent = totals.second,
            refundedCount = totals.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletUiState())

    init {
        viewModelScope.launch {
            rewardsRepository.refreshFromServer()
            rewardsRepository.observeTransactions(500).collect { list ->
                lifetime.value = Triple(
                    list.filter { it.amount > 0 && it.status == CoinStatus.CONFIRMED }.sumOf { it.amount },
                    list.filter { it.amount < 0 && it.status == CoinStatus.CONFIRMED }.sumOf { -it.amount },
                    list.count { it.status == CoinStatus.REJECTED },
                )
            }
        }
    }
}

@Composable
fun WalletScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("ALL") }

    val visible = when (filter) {
        "EARNED" -> ui.transactions.filter { it.amount > 0 }
        "SPENT" -> ui.transactions.filter { it.amount < 0 }
        "PENDING" -> ui.transactions.filter { it.status != CoinStatus.CONFIRMED }
        else -> ui.transactions
    }

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
                Text("V-Coin wallet", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = "Confirmed",
                    value = Formatters.compact(ui.balance.confirmed),
                    unit = "VC",
                    hint = "Server ledger",
                    accent = VTrackTheme.colors.coinGold,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Pending",
                    value = Formatters.compact(ui.balance.pending),
                    unit = "VC",
                    hint = "Awaiting verification",
                    accent = VTrackTheme.colors.warning,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = "Lifetime earned",
                    value = Formatters.compact(ui.lifetimeEarned),
                    unit = "VC",
                    hint = "Across all activity",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Redeemed",
                    value = Formatters.compact(ui.lifetimeSpent),
                    unit = "VC",
                    hint = "${ui.refundedCount} rejected claims",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL", "EARNED", "SPENT", "PENDING").forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option) },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyPane(
                    title = "No transactions yet",
                    message = "Claim step or workout rewards and they will appear here, with the server's " +
                        "reason attached to each entry.",
                )
            }
        } else {
            items(visible.size) { index ->
                val transaction = visible[index]
                val date = Instant.ofEpochMilli(transaction.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                VTrackCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(transaction.type.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${Formatters.relativeDay(date)} • ${transaction.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = (if (transaction.amount >= 0) "+" else "") + transaction.amount,
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                transaction.status == CoinStatus.REJECTED -> MaterialTheme.colorScheme.error
                                transaction.amount >= 0 -> VTrackTheme.colors.coinGold
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            text = transaction.status.name.replace('_', ' '),
                            color = when (transaction.status) {
                                CoinStatus.CONFIRMED -> VTrackTheme.colors.success
                                CoinStatus.PENDING -> VTrackTheme.colors.warning
                                CoinStatus.REJECTED -> MaterialTheme.colorScheme.error
                                CoinStatus.LOCAL_ESTIMATE -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        transaction.rejectionReason?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "About V-Coins", subtitle = "Kept deliberately honest")
                Text(
                    "V-Coins are virtual credits earned and recorded server-side. They are not money, are " +
                        "not transferable, cannot be withdrawn, and every amount shown here comes from the " +
                        "ledger the server wrote — the app displays an estimate before verification and " +
                        "nothing else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Day key: ${TimeUtils.todayKey()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
