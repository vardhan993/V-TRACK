package com.vtrack.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vtrack.app.ui.analytics.AnalyticsScreen
import com.vtrack.app.ui.auth.AuthScreen
import com.vtrack.app.ui.coach.CoachScreen
import com.vtrack.app.ui.coach.PlanScreen
import com.vtrack.app.ui.components.LoadingPane
import com.vtrack.app.ui.earn.AchievementsScreen
import com.vtrack.app.ui.earn.ChallengesScreen
import com.vtrack.app.ui.earn.EarnScreen
import com.vtrack.app.ui.earn.RewardsScreen
import com.vtrack.app.ui.earn.WalletScreen
import com.vtrack.app.ui.home.HomeScreen
import com.vtrack.app.ui.onboarding.OnboardingScreen
import com.vtrack.app.ui.profile.PrivacyScreen
import com.vtrack.app.ui.profile.ProfileScreen
import com.vtrack.app.ui.profile.SettingsScreen
import com.vtrack.app.ui.workout.ExerciseDetailScreen
import com.vtrack.app.ui.workout.LiveWorkoutScreen
import com.vtrack.app.ui.workout.ManualLogScreen
import com.vtrack.app.ui.workout.StrengthSessionScreen
import com.vtrack.app.ui.workout.WorkoutDetailScreen
import com.vtrack.app.ui.workout.WorkoutHistoryScreen
import com.vtrack.app.ui.workout.WorkoutScreen
import kotlinx.coroutines.launch

/**
 * Root of the UI. Decides between the auth flow, onboarding and the five-tab main shell,
 * then owns the single [NavHost] for the whole app so that deep screens (live workout,
 * exercise detail, wallet …) all share one back stack.
 */
@Composable
fun VTrackApp(
    navController: NavHostController = rememberNavController(),
    rootViewModel: AppRootViewModel = hiltViewModel(),
) {
    val state by rootViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (!state.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingPane(label = "Starting V-Track…")
        }
        return
    }

    // Session expiry is surfaced once and then cleared so the user is not nagged.
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) {
            snackbarHostState.showSnackbar("Your session expired. Please sign in again.")
            rootViewModel.dismissSessionExpired()
        }
    }

    val startDestination = when {
        !state.isSignedIn -> Routes.AUTH
        !state.onboardingComplete -> Routes.ONBOARDING
        else -> Routes.MAIN
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.AUTH) {
                    AuthScreen(
                        onAuthenticated = { needsOnboarding ->
                            navController.navigate(
                                if (needsOnboarding) Routes.ONBOARDING else Routes.MAIN,
                            ) {
                                popUpTo(Routes.AUTH) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Routes.MAIN) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.MAIN) {
                    MainShell(
                        rootViewModel = rootViewModel,
                        onNavigate = { route -> navController.navigate(route) },
                        onSnackbar = { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                    )
                }

                composable(
                    route = Routes.LIVE_WORKOUT,
                    arguments = listOf(
                        navArgument("activityType") { type = NavType.StringType },
                        navArgument("indoor") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    LiveWorkoutScreen(
                        activityTypeName = entry.arguments?.getString("activityType").orEmpty(),
                        indoor = entry.arguments?.getBoolean("indoor") ?: false,
                        onFinished = { sessionId ->
                            navController.navigate(Routes.workoutDetail(sessionId)) {
                                popUpTo(Routes.MAIN)
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Routes.STRENGTH_SESSION,
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { entry ->
                    StrengthSessionScreen(
                        sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onFinished = { sessionId ->
                            navController.navigate(Routes.workoutDetail(sessionId)) {
                                popUpTo(Routes.MAIN)
                            }
                        },
                    )
                }

                composable(
                    route = Routes.EXERCISE_DETAIL,
                    arguments = listOf(navArgument("exerciseId") { type = NavType.StringType }),
                ) { entry ->
                    ExerciseDetailScreen(
                        exerciseId = entry.arguments?.getString("exerciseId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKOUT_HISTORY) {
                    WorkoutHistoryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSession = { navController.navigate(Routes.workoutDetail(it)) },
                    )
                }

                composable(
                    route = Routes.WORKOUT_DETAIL,
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { entry ->
                    WorkoutDetailScreen(
                        sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.MANUAL_LOG) {
                    ManualLogScreen(
                        onBack = { navController.popBackStack() },
                        onLogged = { sessionId ->
                            navController.navigate(Routes.workoutDetail(sessionId)) {
                                popUpTo(Routes.MAIN)
                            }
                        },
                    )
                }

                composable(Routes.WALLET) {
                    WalletScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.CHALLENGES) {
                    ChallengesScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.REWARDS) {
                    RewardsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.ACHIEVEMENTS) {
                    AchievementsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PLAN) {
                    PlanScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.ANALYTICS) {
                    AnalyticsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                        onSignedOut = {
                            navController.navigate(Routes.AUTH) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.PRIVACY) {
                    PrivacyScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/**
 * The five-tab shell. Each tab keeps its own back stack so switching tabs preserves
 * scroll position and in-progress filters.
 */
@Composable
private fun MainShell(
    rootViewModel: AppRootViewModel,
    onNavigate: (String) -> Unit,
    onSnackbar: (String) -> Unit,
) {
    val tabController = rememberNavController()
    val backStackEntry by tabController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val state by rootViewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                tabController.navigate(destination.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) {
                                    destination.selectedIcon
                                } else {
                                    destination.unselectedIcon
                                },
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            AnimatedVisibility(visible = !state.isOnline) {
                // Offline is normal for this app; the banner reassures rather than warns.
                Text(
                    text = "Offline — activity is recorded on this device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
            NavHost(
                navController = tabController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenWorkout = { onNavigate(Routes.WORKOUT) },
                        onStartActivity = { type -> onNavigate(Routes.liveWorkout(type.name)) },
                        onOpenAnalytics = { onNavigate(Routes.ANALYTICS) },
                        onOpenHistory = { onNavigate(Routes.WORKOUT_HISTORY) },
                        onOpenEarn = { onNavigate(Routes.EARN) },
                        onMessage = onSnackbar,
                    )
                }
                composable(Routes.WORKOUT) {
                    WorkoutScreen(
                        onStartActivity = { type -> onNavigate(Routes.liveWorkout(type.name)) },
                        onOpenExercise = { onNavigate(Routes.exerciseDetail(it)) },
                        onOpenHistory = { onNavigate(Routes.WORKOUT_HISTORY) },
                        onManualLog = { onNavigate(Routes.MANUAL_LOG) },
                        onStartStrength = { sessionId ->
                            onNavigate(Routes.strengthSession(sessionId))
                        },
                        onMessage = onSnackbar,
                    )
                }
                composable(Routes.EARN) {
                    EarnScreen(
                        onOpenWallet = { onNavigate(Routes.WALLET) },
                        onOpenChallenges = { onNavigate(Routes.CHALLENGES) },
                        onOpenRewards = { onNavigate(Routes.REWARDS) },
                        onOpenAchievements = { onNavigate(Routes.ACHIEVEMENTS) },
                        onMessage = onSnackbar,
                    )
                }
                composable(Routes.COACH) {
                    CoachScreen(
                        onOpenPlan = { onNavigate(Routes.PLAN) },
                        onMessage = onSnackbar,
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onOpenSettings = { onNavigate(Routes.SETTINGS) },
                        onOpenAnalytics = { onNavigate(Routes.ANALYTICS) },
                        onOpenHistory = { onNavigate(Routes.WORKOUT_HISTORY) },
                        onOpenAchievements = { onNavigate(Routes.ACHIEVEMENTS) },
                    )
                }
            }
        }
    }
}

