package com.vtrack.app.ui.workout

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.vtrack.app.data.repository.ExerciseRepository
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.model.ActivityCategory
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.Exercise
import com.vtrack.app.domain.model.Routine
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.ui.components.EmptyPane
import com.vtrack.app.ui.components.MetricTile
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutUiState(
    val profile: UserProfile = UserProfile.empty(),
    val exercises: List<Exercise> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val category: ActivityCategory? = null,
    val searchQuery: String = "",
    val searchResults: List<Exercise> = emptyList(),
    val recentCount: Int = 0,
    val totalSets: Int = 0,
    val message: String? = null,
)

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<ActivityCategory?>(null)
    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<Exercise>>(emptyList())
    private val message = MutableStateFlow<String?>(null)
    private val totalSets = MutableStateFlow(0)

    val ui: StateFlow<WorkoutUiState> = combine(
        profileRepository.observeProfile(),
        exerciseRepository.observeAll(),
        workoutRepository.observeRoutines(),
        filter,
        query,
    ) { profile, all, routines, category, q ->
        WorkoutUiState(
            profile = profile,
            exercises = if (category == null) all else all.filter { it.category == category },
            routines = routines,
            category = category,
            searchQuery = q,
        )
    }
        .combine(results) { state, searchResults -> state.copy(searchResults = searchResults) }
        .combine(message) { state, msg -> state.copy(message = msg) }
        .combine(totalSets) { state, sets -> state.copy(totalSets = sets) }
        .combine(workoutRepository.observeCompletedCount()) { state, count ->
            state.copy(recentCount = count)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUiState())

    init {
        viewModelScope.launch { exerciseRepository.ensureSeeded() }
        viewModelScope.launch { totalSets.value = workoutRepository.totalSets() }
        viewModelScope.launch {
            query.collect { q ->
                results.value = if (q.length < 2) emptyList() else exerciseRepository.search(q)
            }
        }
    }

    fun setCategory(category: ActivityCategory?) {
        filter.value = category
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun createStarterRoutine(routine: Routine, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            workoutRepository.saveRoutine(routine)
            onCreated(routine.id)
            message.value = "Routine saved"
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

@Composable
fun WorkoutScreen(
    onStartActivity: (ActivityType) -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onManualLog: () -> Unit,
    onStartStrength: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewModel: WorkoutViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var selectedActivity by remember { mutableStateOf(ActivityType.WALKING) }

    androidx.compose.runtime.LaunchedEffect(ui.message) {
        ui.message?.let {
            onMessage(it)
            viewModel.clearMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            VTrackCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(
                    title = "Start a live session",
                    subtitle = "Sensors recorded on this device",
                    actionLabel = "History",
                    onAction = onOpenHistory,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ActivityType.entries.toList()) { activity ->
                        FilterChip(
                            selected = selectedActivity == activity,
                            onClick = { selectedActivity = activity },
                            label = { Text("${activity.emoji} ${activity.label}") },
                        )
                    }
                }
                Text(
                    text = when {
                        selectedActivity.usesGps && selectedActivity.countsSteps ->
                            "Uses GPS for the route and your step sensor for cadence."
                        selectedActivity.usesGps -> "Uses GPS. You can start this indoors for a no-GPS session."
                        selectedActivity.isStrength ->
                            "Track sets, reps and rest timers, with personal records per exercise."
                        else -> "Uses your movement sensors. Works with no GPS."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryActionButton(
                        text = "Start ${selectedActivity.label}",
                        onClick = { onStartActivity(selectedActivity) },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.PlayArrow,
                    )
                    SecondaryActionButton(
                        text = "Log manually",
                        onClick = onManualLog,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Add,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = "Sessions logged",
                    value = ui.recentCount.toString(),
                    hint = "Finished workouts",
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "Strength sets",
                    value = ui.totalSets.toString(),
                    hint = "All time",
                    accent = VTrackTheme.colors.chartPurple,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            VTrackCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(
                    title = "Strength session",
                    subtitle = "Sets, reps, weights and rest timers",
                )
                Text(
                    "Start a strength session to log each set with a rest timer and automatic " +
                        "personal-record tracking per exercise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryActionButton(
                    text = "Open strength logger",
                    onClick = { onStartStrength("") },
                    icon = Icons.Filled.FitnessCenter,
                )
            }
        }

        item {
            VTrackCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(
                    title = "Exercise library",
                    subtitle = "${ui.exercises.size} exercises with technique notes",
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = ui.category == null,
                            onClick = { viewModel.setCategory(null) },
                            label = { Text("All") },
                        )
                    }
                    items(ActivityCategory.entries.toList()) { category ->
                        FilterChip(
                            selected = ui.category == category,
                            onClick = { viewModel.setCategory(category) },
                            label = { Text(category.label) },
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = ui.searchQuery,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search exercises") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = if (ui.searchResults.isNotEmpty()) ui.searchResults else ui.exercises
                if (list.isEmpty()) {
                    EmptyPane(
                        title = "No exercises yet",
                        message = "The bundled library seeds on first launch. Pull to refresh or try another filter.",
                    )
                } else {
                    list.take(12).forEach { exercise ->
                        ExerciseRow(exercise = exercise, onClick = { onOpenExercise(exercise.id) })
                    }
                    if (list.size > 12) {
                        Text(
                            "Showing 12 of ${list.size}. Use search to narrow it down.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            VTrackCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionHeader(
                    title = "Your routines",
                    subtitle = if (ui.routines.isEmpty()) {
                        "Build one from the library"
                    } else {
                        "${ui.routines.size} saved"
                    },
                )
                if (ui.routines.isEmpty()) {
                    Text(
                        "A routine is an ordered list of exercises with sets and reps that you can " +
                            "schedule per weekday. Use 'Build starter routine' below to create one from " +
                            "your primary goal, then edit it in the strength logger.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryActionButton(
                        text = "Build starter routine from my goal",
                        onClick = {
                            val routine = StarterRoutines.forGoal(ui.profile)
                            viewModel.createStarterRoutine(routine) { id -> onStartStrength(id) }
                        },
                    )
                } else {
                    ui.routines.forEach { routine ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(routine.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${routine.activityType.label} • ${routine.items.size} exercises",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusChip(
                                text = routine.activityType.emoji,
                                color = VTrackTheme.colors.chartTeal,
                            )
                        }
                    }
                }
            }
        }

        item {
            VTrackCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = onOpenHistory,
                accent = VTrackTheme.colors.chartBlue,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = VTrackTheme.colors.chartBlue)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Workout history", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Every session with pace, distance, sets and personal records.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun ExerciseRow(exercise: Exercise, onClick: () -> Unit) {
    VTrackCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${exercise.primaryMuscle} • ${exercise.equipment} • ${exercise.difficulty.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when {
                    exercise.defaultSets != null && exercise.defaultReps != null ->
                        "${exercise.defaultSets}×${exercise.defaultReps}"
                    exercise.defaultSeconds != null -> "${exercise.defaultSeconds}s"
                    else -> ""
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            exercise.instructions.firstOrNull().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/** Goal-aligned starter routines so a new user is never staring at an empty planner. */
object StarterRoutines {

    fun forGoal(profile: UserProfile): Routine {
        val (name, exercises) = when (profile.primaryGoal) {
            com.vtrack.app.domain.model.PrimaryGoal.BUILD_STRENGTH -> "Full-body strength A" to listOf(
                "seed.bodyweight-squat" to "3×10",
                "seed.push-up" to "3×8",
                "seed.inverted-row" to "3×8",
                "seed-glute-bridge" to "3×12",
                "seed.plank" to "3×40s",
            )
            com.vtrack.app.domain.model.PrimaryGoal.RECOVER_MOBILITY -> "Mobility flow" to listOf(
                "seed.cat-cow" to "2×10",
                "seed.worlds-greatest-stretch" to "2×5",
                "seed.90-90-hip-switch" to "2×10",
                "seed.downward-dog" to "3×45s",
                "seed.childs-pose" to "2×60s",
            )
            com.vtrack.app.domain.model.PrimaryGoal.IMPROVE_ENDURANCE -> "Conditioning circuit" to listOf(
                "seed.jumping-jack" to "3×60s",
                "seed.mountain-climber" to "3×40s",
                "seed.bodyweight-squat" to "3×15",
                "seed.burpee" to "3×8",
            )
            else -> "Everyday movement" to listOf(
                "seed.bodyweight-squat" to "3×12",
                "seed.push-up" to "3×8",
                "seed.hip-hinge-good-morning" to "3×12",
                "seed.dead-bug" to "3×10",
                "seed.plank" to "3×30s",
            )
        }
        return Routine(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            description = "Starter routine generated from your primary goal (${profile.primaryGoal.label}). Edit it any time.",
            activityType = ActivityType.STRENGTH,
            scheduledWeekdays = emptyList(),
            items = exercises.mapIndexed { index, (exerciseId, prescription) ->
                val parts = prescription.split("×")
                com.vtrack.app.domain.model.RoutineItem(
                    position = index,
                    exerciseId = exerciseId,
                    label = exerciseId.removePrefix("seed.").replace('-', ' '),
                    sets = parts.getOrNull(0)?.toIntOrNull() ?: 3,
                    reps = parts.getOrNull(1)?.removeSuffix("s")?.toIntOrNull(),
                    durationSeconds = if (prescription.endsWith("s")) {
                        parts.getOrNull(1)?.removeSuffix("s")?.toIntOrNull()
                    } else {
                        null
                    },
                    restSeconds = 60,
                )
            },
        )
    }
}
