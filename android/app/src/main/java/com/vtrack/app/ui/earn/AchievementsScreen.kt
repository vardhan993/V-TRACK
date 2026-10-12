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
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.domain.model.Achievement
import com.vtrack.app.ui.components.ProgressBarThin
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.StatusChip
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AchievementsUiState(
    val unlocked: List<Achievement> = emptyList(),
    val inProgress: List<Achievement> = emptyList(),
)

@HiltViewModel
class AchievementsViewModel @Inject constructor(
    private val rewardsRepository: RewardsRepository,
) : ViewModel() {

    val ui: StateFlow<AchievementsUiState> = rewardsRepository.observeAchievements()
        .map { achievements ->
            AchievementsUiState(
                unlocked = achievements.filter { it.unlocked },
                inProgress = achievements.filterNot { it.unlocked },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AchievementsUiState())

    init {
        viewModelScope.launch {
            rewardsRepository.seedAchievementCatalog()
            rewardsRepository.refreshFromServer()
        }
    }
}

@Composable
fun AchievementsScreen(
    onBack: () -> Unit,
    viewModel: AchievementsViewModel = hiltViewModel(),
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
                Text("Achievements", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            SectionHeader(
                title = "Unlocked",
                subtitle = "${ui.unlocked.size} of ${ui.unlocked.size + ui.inProgress.size}",
            )
        }

        items(ui.unlocked.size) { index ->
            AchievementCard(ui.unlocked[index])
        }

        item {
            SectionHeader(title = "In progress", subtitle = "${ui.inProgress.size} to go")
        }

        items(ui.inProgress.size) { index ->
            AchievementCard(ui.inProgress[index])
        }

        item {
            VTrackCard {
                Text(
                    "Achievements are evaluated on device from your own records for instant feedback, and " +
                        "the server re-evaluates them whenever it pays a bonus — so the two always agree.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AchievementCard(achievement: Achievement) {
    VTrackCard(accent = if (achievement.unlocked) VTrackTheme.colors.coinGold else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(achievement.icon, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(achievement.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    achievement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (achievement.unlocked) {
                StatusChip(
                    text = achievement.unlockedAt?.let {
                        Formatters.dayLabel(
                            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        )
                    } ?: "UNLOCKED",
                    color = VTrackTheme.colors.success,
                )
            }
        }
        if (!achievement.unlocked) {
            ProgressBarThin(progress = achievement.fraction)
            Text(
                "${Formatters.typed(achievement.progress, 0)} / ${Formatters.typed(achievement.threshold, 0)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
