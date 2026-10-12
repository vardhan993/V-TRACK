package com.vtrack.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector

/** Every route in the app. String routes keep navigation readable and testable. */
object Routes {
    const val AUTH = "auth"
    const val ONBOARDING = "onboarding"

    const val MAIN = "main"
    const val HOME = "home"
    const val WORKOUT = "workout"
    const val EARN = "earn"
    const val COACH = "coach"
    const val PROFILE = "profile"

    /** Live workout carries the chosen activity so the screen never guesses. */
    const val LIVE_WORKOUT = "live?activityType={activityType}&indoor={indoor}"
    const val STRENGTH_SESSION = "strength/{sessionId}"
    const val EXERCISE_DETAIL = "exercise/{exerciseId}"
    const val WORKOUT_HISTORY = "history"
    const val WORKOUT_DETAIL = "session/{sessionId}"
    const val MANUAL_LOG = "manual-log"
    const val ROUTINES = "routines"

    const val WALLET = "wallet"
    const val CHALLENGES = "challenges"
    const val REWARDS = "rewards"
    const val ACHIEVEMENTS = "achievements"

    const val PLAN = "plan"
    const val ANALYTICS = "analytics"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val PR = "records"

    fun liveWorkout(activityTypeName: String, indoor: Boolean = false) =
        "live?activityType=$activityTypeName&indoor=$indoor"

    fun strengthSession(sessionId: String) = "strength/$sessionId"
    fun exerciseDetail(exerciseId: String) = "exercise/$exerciseId"
    fun workoutDetail(sessionId: String) = "session/$sessionId"
}

/** The five primary destinations of the bottom bar. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    WORKOUT(Routes.WORKOUT, "Workout", Icons.Filled.DirectionsRun, Icons.Outlined.DirectionsRun),
    EARN(Routes.EARN, "Earn", Icons.Filled.Paid, Icons.Outlined.Paid),
    COACH(Routes.COACH, "AI Coach", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    PROFILE(Routes.PROFILE, "Profile", Icons.Filled.Person, Icons.Outlined.Person),
}
