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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.data.repository.ProfileRepository
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

data class PrivacyUiState(
    val exporting: Boolean = false,
    val exportPayload: String? = null,
    val deleting: Boolean = false,
    val message: String? = null,
    val deleteRequiresOnline: Boolean = false,
)

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(PrivacyUiState())
    val ui: StateFlow<PrivacyUiState> = _ui.asStateFlow()

    fun export() {
        _ui.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch {
            profileRepository.exportData().fold(
                onSuccess = { payload -> _ui.update { it.copy(exporting = false, exportPayload = payload) } },
                onFailure = { error ->
                    _ui.update { it.copy(exporting = false, message = error.userMessage()) }
                },
            )
        }
    }

    fun closeExport() = _ui.update { it.copy(exportPayload = null) }

    /**
     * Deletion has to reach the server first: leaving records behind after a user asked for
     * deletion would be worse than refusing, so an offline attempt reports rather than
     * silently discarding only the local copy.
     */
    fun deleteAccount(onDeleted: () -> Unit) {
        _ui.update { it.copy(deleting = true, message = null) }
        viewModelScope.launch {
            profileRepository.deleteAccount().fold(
                onSuccess = {
                    _ui.update { it.copy(deleting = false) }
                    onDeleted()
                },
                onFailure = { error ->
                    _ui.update {
                        it.copy(
                            deleting = false,
                            message = error.userMessage(),
                            deleteRequiresOnline = true,
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    viewModel: PrivacyViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmationText by remember { mutableStateOf("") }

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
                Text("Privacy & data", style = MaterialTheme.typography.headlineSmall)
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "What V-Track stores", subtitle = "Plain language, no surprises")
                Text(
                    "• On this device: steps in hourly buckets, daily totals, workouts, GPS tracks, " +
                        "strength sets, goals and local reward estimates, all in a Room database.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• On our server (only when signed in): your account, profile, daily activity totals, " +
                        "workouts, the V-Coin ledger, challenge progress and redemption requests.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Never stored: your password in plain text (PBKDF2-HMAC-SHA256 hash only), GPS " +
                        "routes (they stay on the device unless you opt in), or any third-party analytics.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Permissions", subtitle = "Each one is optional")
                Text(
                    "• Physical activity: hardware step counting.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Location: only while an outdoor workout is running, for the route and distance.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Notifications: the live-workout controls and an optional evening reminder.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• Health Connect: read-only, for steps and sessions other apps already recorded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                StatusChip(
                    text = "REVOKE ANY TIME IN SYSTEM SETTINGS",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Your controls", subtitle = "Export or erase")
                PrimaryActionButton(
                    text = "Export my data as JSON",
                    onClick = viewModel::export,
                    loading = ui.exporting,
                )
                ui.exportPayload?.let { payload ->
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { },
                        label = { Text("Export") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    SecondaryActionButton(text = "Close", onClick = viewModel::closeExport)
                }
                ui.message?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            VTrackCard(accent = MaterialTheme.colorScheme.error) {
                SectionHeader(title = "Delete account", subtitle = "Removes server and local data")
                Text(
                    "Deleting erases your profile, activity, workouts and the coin ledger from the server, " +
                        "then wipes the local database and credentials. Step and workout history cannot be " +
                        "recovered afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryActionButton(
                    text = "Delete my account",
                    onClick = { confirmDelete = true },
                    icon = Icons.Filled.DeleteForever,
                )
            }
        }

        item {
            Text(
                "V-Track is designed to work without sending anything anywhere. Sign-in, cloud backup and " +
                    "reward verification are the only features that require the network.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = {
                confirmDelete = false
                confirmationText = ""
            },
            title = { Text("Delete your account?") },
            text = {
                Column {
                    Text("Type DELETE to confirm. This erases server and local data permanently.")
                    OutlinedTextField(
                        value = confirmationText,
                        onValueChange = { confirmationText = it },
                        label = { Text("Type DELETE") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteAccount(onDeleted = onBack)
                    },
                    enabled = confirmationText.trim().uppercase() == "DELETE",
                ) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false; confirmationText = "" }) { Text("Cancel") }
            },
        )
    }
}
