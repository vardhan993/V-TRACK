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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.core.error.userMessage
import com.vtrack.app.core.util.Formatters
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.repository.AuthRepository
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.ProfileUpdate
import com.vtrack.app.data.repository.SyncEngine
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.tracking.HealthConnectManager
import com.vtrack.app.tracking.StepTrackingService
import com.vtrack.app.ui.components.InlineActionRow
import com.vtrack.app.ui.components.PrimaryActionButton
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
import javax.inject.Inject

data class SettingsUiState(
    val profile: UserProfile = UserProfile.empty(),
    val pendingSync: Int = 0,
    val healthConnectStatus: String = "Unknown",
    val message: String? = null,
    val exporting: Boolean = false,
    val exportPayload: String? = null,
    val signingOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val syncEngine: SyncEngine,
    private val healthConnect: HealthConnectManager,
    @ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val local = MutableStateFlow(SettingsUiState())

    val ui: StateFlow<SettingsUiState> = combine(
        profileRepository.observeProfile(),
        syncEngine.observePendingCount(),
        local,
    ) { profile, pending, localState ->
        localState.copy(profile = profile, pendingSync = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            val status = when (healthConnect.sdkStatus()) {
                HealthConnectManager.SdkStatus.AVAILABLE -> "Available on this device"
                HealthConnectManager.SdkStatus.UPDATE_REQUIRED -> "Health Connect needs an update"
                HealthConnectManager.SdkStatus.UNAVAILABLE -> "Not available on this device"
            }
            local.value = local.value.copy(healthConnectStatus = status)
        }
    }

    fun setStepGoal(steps: Int) {
        viewModelScope.launch {
            profileRepository.setStepGoal(steps).fold(
                onSuccess = { local.value = local.value.copy(message = "Step goal updated") },
                onFailure = { local.value = local.value.copy(message = it.userMessage()) },
            )
        }
    }

    fun setWeeklyWorkouts(value: Int) {
        viewModelScope.launch {
            profileRepository.updateProfile(ProfileUpdate(weeklyWorkoutTarget = value.coerceIn(2, 6)))
        }
    }

    fun setUnits(units: UnitSystem) {
        viewModelScope.launch { profileRepository.updateProfile(ProfileUpdate(unitSystem = units)) }
    }

    fun setHealthConnect(enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.setHealthConnectEnabled(enabled)
            if (enabled) {
                val outcome = healthConnect.importRecent(days = 7)
                local.value = local.value.copy(
                    message = when {
                        !outcome.available -> "Health Connect is not available on this device."
                        outcome.permissionMissing -> "Grant Health Connect read access to import activity."
                        else -> "Imported ${outcome.importedRecords} record(s) from Health Connect."
                    },
                )
            } else {
                local.value = local.value.copy(message = "Health Connect import disabled")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val report = syncEngine.sync()
            local.value = local.value.copy(
                message = when {
                    report.skipped -> "Sync skipped: ${report.reason}"
                    else -> "Synced ${report.daysSynced} day(s), ${report.workoutsSynced} workout(s). " +
                        "${report.remaining} item(s) still queued."
                },
            )
        }
    }

    fun exportData() {
        local.value = local.value.copy(exporting = true)
        viewModelScope.launch {
            profileRepository.exportData().fold(
                onSuccess = { payload ->
                    local.value = local.value.copy(exporting = false, exportPayload = payload)
                },
                onFailure = { error ->
                    local.value = local.value.copy(exporting = false, message = error.userMessage())
                },
            )
        }
    }

    fun clearExport() {
        local.value = local.value.copy(exportPayload = null)
    }

    /** Opt-in switch for coaching data sharing; the app works fully when it is off. */
    fun setShareWithCoach(enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.updateProfile(ProfileUpdate(shareActivityWithCoach = enabled))
            local.value = local.value.copy(
                message = if (enabled) {
                    "The coach may now use your activity for suggestions."
                } else {
                    "Activity sharing for coaching is off. Plans will be generated on-device only."
                },
            )
        }
    }

    /** GPS routes stay local unless the user explicitly turns this on. */
    fun setShareRoutes(enabled: Boolean) {
        viewModelScope.launch {
            profileRepository.updateProfile(ProfileUpdate(shareRoutes = enabled))
            local.value = local.value.copy(
                message = if (enabled) {
                    "Route upload enabled. New workouts may include their GPS track."
                } else {
                    "Route upload disabled. Routes stay on this device."
                },
            )
        }
    }

    fun signOut(onDone: () -> Unit) {
        local.value = local.value.copy(signingOut = true)
        viewModelScope.launch {
            authRepository.logout()
            StepTrackingService.stop(appContext)
            local.value = local.value.copy(signingOut = false)
            onDone()
        }
    }

    fun clearMessage() {
        local.value = local.value.copy(message = null)
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var locationSharing by remember { mutableStateOf(ui.profile.shareRoutes) }

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
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
        }

        ui.message?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary)
                SecondaryActionButton(text = "Dismiss", onClick = viewModel::clearMessage)
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Daily target", subtitle = "Used for the ring, streaks and challenges")
                Text("${Formatters.steps(ui.profile.stepGoal)} steps", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = ui.profile.stepGoal.toFloat(),
                    onValueChange = { viewModel.setStepGoal(it.toInt() / 500 * 500) },
                    valueRange = 2_000f..20_000f,
                    steps = 35,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5_000, 8_000, 10_000, 12_000).forEach { value ->
                        FilterChip(
                            selected = ui.profile.stepGoal == value,
                            onClick = { viewModel.setStepGoal(value) },
                            label = { Text(Formatters.steps(value)) },
                        )
                    }
                }
                Text("Sessions per week: ${ui.profile.weeklyWorkoutTarget}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..6).forEach { value ->
                        FilterChip(
                            selected = ui.profile.weeklyWorkoutTarget == value,
                            onClick = { viewModel.setWeeklyWorkouts(value) },
                            label = { Text("$value") },
                        )
                    }
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Units", subtitle = "Display only — data is stored in SI units")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSystem.entries.forEach { units ->
                        FilterChip(
                            selected = ui.profile.unitSystem == units,
                            onClick = { viewModel.setUnits(units) },
                            label = { Text(units.name) },
                        )
                    }
                }
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Integrations", subtitle = "Optional and off by default")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = VTrackTheme.colors.chartTeal)
                            Spacer(Modifier.width(8.dp))
                            Text("Health Connect (read-only)", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            ui.healthConnectStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ui.profile.healthConnectEnabled,
                        onCheckedChange = viewModel::setHealthConnect,
                    )
                }
                Text(
                    "V-Track never writes to Health Connect, and imported records are de-duplicated so " +
                        "nothing is counted twice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Sync", subtitle = "${ui.pendingSync} change(s) queued")
                InlineActionRow(
                    icon = Icons.Filled.CloudSync,
                    label = "Sync now",
                    onClick = viewModel::syncNow,
                    trailing = {
                        StatusChip(
                            text = if (ui.pendingSync == 0) "UP TO DATE" else "PENDING",
                            color = if (ui.pendingSync == 0) VTrackTheme.colors.success else VTrackTheme.colors.warning,
                        )
                    },
                )
                Text(
                    "Activity is recorded locally first. Sync retries with exponential backoff and every " +
                        "push is idempotent, so a retry can never duplicate a workout or a coin reward.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            VTrackCard {
                SectionHeader(title = "Data & privacy", subtitle = "You control both")
                InlineActionRow(
                    icon = Icons.Filled.PrivacyTip,
                    label = "Privacy settings",
                    onClick = onOpenPrivacy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share activity with the coach", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When off, nothing about your activity is sent to the server for coaching.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ui.profile.shareActivityWithCoach,
                        onCheckedChange = viewModel::setShareWithCoach,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow GPS route upload", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Off by default. Routes stay on this device unless you turn this on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = locationSharing,
                        onCheckedChange = { enabled ->
                            locationSharing = enabled
                            viewModel.setShareRoutes(enabled)
                        },
                    )
                }
                PrimaryActionButton(
                    text = "Export my data (JSON)",
                    onClick = viewModel::exportData,
                    loading = ui.exporting,
                )
                ui.exportPayload?.let { payload ->
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { },
                        label = { Text("Export preview") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8,
                    )
                    SecondaryActionButton(text = "Close preview", onClick = viewModel::clearExport)
                }
            }
        }

        item {
            VTrackCard(accent = MaterialTheme.colorScheme.error) {
                SectionHeader(title = "Account", subtitle = "Sign out keeps your local data")
                InlineActionRow(
                    icon = Icons.Filled.Logout,
                    label = "Sign out",
                    onClick = { viewModel.signOut(onSignedOut) },
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Signing out revokes the refresh token on the server and clears the tokens from the " +
                        "Android Keystore. Recorded activity stays on the device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
