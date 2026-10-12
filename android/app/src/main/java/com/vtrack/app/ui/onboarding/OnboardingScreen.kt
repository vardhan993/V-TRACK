package com.vtrack.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.ProfileUpdate
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.FitnessLevel
import com.vtrack.app.domain.model.PrimaryGoal
import com.vtrack.app.tracking.StepTrackingService
import com.vtrack.app.ui.components.PrimaryActionButton
import com.vtrack.app.ui.components.SecondaryActionButton
import com.vtrack.app.ui.components.SectionHeader
import com.vtrack.app.ui.components.VTrackCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0,
    val displayName: String = "",
    val goal: PrimaryGoal = PrimaryGoal.STAY_ACTIVE,
    val level: FitnessLevel = FitnessLevel.BEGINNER,
    val activities: Set<ActivityType> = setOf(ActivityType.WALKING),
    val weeklyWorkoutTarget: Int = 3,
    val stepGoal: Int = 8_000,
    val heightCm: String = "",
    val weightKg: String = "",
    val birthYear: String = "",
    val healthConnect: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val stepCount: Int get() = 4
    val progress: Float get() = (step + 1).toFloat() / stepCount
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUiState())
    val ui: StateFlow<OnboardingUiState> = _ui.asStateFlow()

    fun next() = _ui.update { it.copy(step = (it.step + 1).coerceAtMost(it.stepCount - 1)) }
    fun back() = _ui.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }
    fun setDisplayName(v: String) = _ui.update { it.copy(displayName = v) }
    fun setGoal(v: PrimaryGoal) = _ui.update { it.copy(goal = v) }
    fun setLevel(v: FitnessLevel) = _ui.update { it.copy(level = v) }
    fun setWeeklyWorkouts(v: Int) = _ui.update { it.copy(weeklyWorkoutTarget = v.coerceIn(2, 6)) }
    fun setStepGoal(v: Int) = _ui.update { it.copy(stepGoal = v.coerceIn(1_000, 25_000)) }
    fun setHeight(v: String) = _ui.update { it.copy(heightCm = v.filter { c -> c.isDigit() || c == '.' }) }
    fun setWeight(v: String) = _ui.update { it.copy(weightKg = v.filter { c -> c.isDigit() || c == '.' }) }
    fun setBirthYear(v: String) = _ui.update { it.copy(birthYear = v.filter { c -> c.isDigit() }.take(4)) }
    fun setHealthConnect(v: Boolean) = _ui.update { it.copy(healthConnect = v) }

    fun toggleActivity(activity: ActivityType) = _ui.update { state ->
        val next = state.activities.toMutableSet()
        if (!next.add(activity)) next.remove(activity)
        state.copy(activities = if (next.isEmpty()) setOf(activity) else next)
    }

    fun finish(onDone: () -> Unit) {
        val state = _ui.value
        _ui.value = state.copy(saving = true, error = null)
        viewModelScope.launch {
            val current = profileRepository.ensureLocalProfile()
            val result = profileRepository.completeOnboarding(
                current.copy(
                    displayName = state.displayName.ifBlank { current.displayName },
                    primaryGoal = state.goal,
                    fitnessLevel = state.level,
                    preferredActivities = state.activities.toList(),
                    weeklyWorkoutTarget = state.weeklyWorkoutTarget,
                    stepGoal = state.stepGoal,
                    heightCm = state.heightCm.toDoubleOrNull() ?: current.heightCm,
                    weightKg = state.weightKg.toDoubleOrNull() ?: current.weightKg,
                    birthYear = state.birthYear.toIntOrNull()?.takeIf { it in 1900..2020 } ?: current.birthYear,
                    healthConnectEnabled = state.healthConnect || current.healthConnectEnabled,
                ),
            )
            result.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(saving = false)
                    onDone()
                },
                onFailure = { throwable ->
                    // Onboarding data is stored locally regardless, so the user is never stuck.
                    _ui.value = _ui.value.copy(saving = false, error = "Saved offline: ${throwable.message}")
                    onDone()
                },
            )
        }
    }
}

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACTIVITY_RECOGNITION] == true) {
            StepTrackingService.start(context)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { ui.progress },
            modifier = Modifier.fillMaxWidth(),
        )

        when (ui.step) {
            0 -> WelcomeStep()
            1 -> AboutYouStep(
                ui = ui,
                onName = viewModel::setDisplayName,
                onHeight = viewModel::setHeight,
                onWeight = viewModel::setWeight,
                onBirthYear = viewModel::setBirthYear,
                onLevel = viewModel::setLevel,
            )
            2 -> GoalsStep(
                ui = ui,
                onGoal = viewModel::setGoal,
                onActivity = viewModel::toggleActivity,
                onStepGoal = viewModel::setStepGoal,
                onWeekly = viewModel::setWeeklyWorkouts,
            )
            else -> PermissionsStep(
                healthConnect = ui.healthConnect,
                onHealthConnect = viewModel::setHealthConnect,
                onRequestActivityPermission = {
                    val permissions = buildList {
                        add(Manifest.permission.ACTIVITY_RECOGNITION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.toTypedArray()
                    activityPermissionLauncher.launch(permissions)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }

        if (ui.error != null) {
            Text(ui.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(4.dp))
        if (ui.step < ui.stepCount - 1) {
            PrimaryActionButton(text = "Continue", onClick = viewModel::next)
            if (ui.step > 0) {
                SecondaryActionButton(text = "Back", onClick = viewModel::back)
            }
        } else {
            PrimaryActionButton(
                text = "Start tracking",
                onClick = { viewModel.finish(onFinished) },
                loading = ui.saving,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WelcomeStep() {
    VTrackCard {
        Text("Welcome to V-Track", style = MaterialTheme.typography.headlineSmall)
        Text(
            "V-Track counts your real steps with your phone's sensors, records GPS workouts, " +
                "and pays virtual V-Coins for verified activity. A rules-based coach turns the data " +
                "into a weekly plan you can actually follow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Everything except sign-in and coin verification works with no network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AboutYouStep(
    ui: OnboardingUiState,
    onName: (String) -> Unit,
    onHeight: (String) -> Unit,
    onWeight: (String) -> Unit,
    onBirthYear: (String) -> Unit,
    onLevel: (FitnessLevel) -> Unit,
) {
    VTrackCard {
        SectionHeader(
            title = "About you",
            subtitle = "All optional — they only improve calorie and plan estimates",
        )
        OutlinedTextField(
            value = ui.displayName,
            onValueChange = onName,
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = ui.heightCm,
                onValueChange = onHeight,
                label = { Text("Height (cm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = ui.weightKg,
                onValueChange = onWeight,
                label = { Text("Weight (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = ui.birthYear,
            onValueChange = onBirthYear,
            label = { Text("Birth year") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Experience level", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitnessLevel.entries.forEach { level ->
                FilterChip(
                    selected = ui.level == level,
                    onClick = { onLevel(level) },
                    label = { Text(level.label) },
                )
            }
        }
    }
}

@Composable
private fun GoalsStep(
    ui: OnboardingUiState,
    onGoal: (PrimaryGoal) -> Unit,
    onActivity: (ActivityType) -> Unit,
    onStepGoal: (Int) -> Unit,
    onWeekly: (Int) -> Unit,
) {
    VTrackCard {
        SectionHeader(title = "What are you working towards?", subtitle = "This shapes your weekly plan")
        PrimaryGoal.entries.forEach { goal ->
            FilterChip(
                selected = ui.goal == goal,
                onClick = { onGoal(goal) },
                label = { Text(goal.label) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.goal == goal) {
                Text(
                    goal.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    VTrackCard {
        SectionHeader(title = "Activities you enjoy", subtitle = "Plans favour what you'll actually do")
        ActivityType.quickStart.plus(listOf(ActivityType.YOGA, ActivityType.HIKING, ActivityType.CYCLING))
            .distinct()
            .chunked(2)
            .forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { activity ->
                        FilterChip(
                            selected = activity in ui.activities,
                            onClick = { onActivity(activity) },
                            label = { Text("${activity.emoji} ${activity.label}") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
    }

    VTrackCard {
        SectionHeader(title = "Targets", subtitle = "You can change these any time in Settings")
        Text("Daily step goal: ${ui.stepGoal}", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5_000, 8_000, 10_000, 12_000).forEach { value ->
                FilterChip(
                    selected = ui.stepGoal == value,
                    onClick = { onStepGoal(value) },
                    label = { Text("${value / 1000}k") },
                )
            }
        }
        Text("Sessions per week: ${ui.weeklyWorkoutTarget}", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (2..6).forEach { value ->
                FilterChip(
                    selected = ui.weeklyWorkoutTarget == value,
                    onClick = { onWeekly(value) },
                    label = { Text("$value") },
                )
            }
        }
    }
}

@Composable
private fun PermissionsStep(
    healthConnect: Boolean,
    onHealthConnect: (Boolean) -> Unit,
    onRequestActivityPermission: () -> Unit,
) {
    VTrackCard {
        SectionHeader(
            title = "Permissions",
            subtitle = "V-Track asks for the minimum it needs, and works with none of them",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Physical activity", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Required for step counting from your phone's hardware sensor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PrimaryActionButton(text = "Allow step access", onClick = onRequestActivityPermission)
        Text(
            "Location is requested later, only when you start an outdoor workout.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    VTrackCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Health Connect (optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Read-only. Lets V-Track include steps and workouts other apps already recorded. " +
                        "V-Track never writes to Health Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = healthConnect, onCheckedChange = onHealthConnect)
        }
    }
}
