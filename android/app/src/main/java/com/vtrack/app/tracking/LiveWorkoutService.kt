package com.vtrack.app.tracking

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.domain.model.ActivityType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps a live workout alive while the screen is off.
 *
 * Responsibilities:
 *  - hold the foreground notification (so Android lets the process keep running),
 *  - own the GPS subscription for outdoor activities,
 *  - forward notification actions (pause/resume/stop) to [LiveWorkoutController].
 *
 * All workout arithmetic lives in the controller, so this class stays thin.
 */
@AndroidEntryPoint
class LiveWorkoutService : Service() {

    @Inject lateinit var controller: LiveWorkoutController
    @Inject lateinit var locationTracker: LocationTracker
    @Inject lateinit var profileRepository: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var gpsStarted = false
    private var imperial = false

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        scope.launch { imperial = profileRepository.currentProfile().unitSystem.name == "IMPERIAL" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP -> scope.launch {
                controller.stop()
                stopSelf()
            }
            else -> startForegroundTracking(intent)
        }
        return START_STICKY
    }

    private fun startForegroundTracking(intent: Intent?) {
        val activityTypeName = intent?.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: ActivityType.WALKING.name
        val activity = ActivityType.fromStorage(activityTypeName)
        val indoor = intent?.getBooleanExtra(EXTRA_INDOOR, false) ?: false

        val notification = Notifications.liveWorkout(
            context = this,
            activityLabel = activity.label,
            elapsedSeconds = controller.state.value.elapsedSeconds,
            distanceMeters = controller.state.value.distanceMeters,
            calories = controller.state.value.calories,
            paused = controller.state.value.status == LiveWorkoutState.Status.PAUSED,
            unitImperial = imperial,
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_LIVE_WORKOUT, notification, type)

        if (!indoor && activity.usesGps && !gpsStarted) {
            gpsStarted = locationTracker.start(
                onPoint = { controller.onPoint(it) },
                onProviderDisabled = { controller.onGpsAvailability(false) },
            )
            controller.onGpsAvailability(gpsStarted)
            if (!gpsStarted) {
                controller.onGpsAvailability(false)
            }
        }

        notificationJob?.cancel()
        notificationJob = scope.launch {
            while (true) {
                val state = controller.state.value
                if (!state.isActive) break
                Notifications.notify(
                    this@LiveWorkoutService,
                    Notifications.ID_LIVE_WORKOUT,
                    Notifications.liveWorkout(
                        context = this@LiveWorkoutService,
                        activityLabel = state.activityType.label,
                        elapsedSeconds = state.elapsedSeconds,
                        distanceMeters = state.distanceMeters,
                        calories = state.calories,
                        paused = state.status == LiveWorkoutState.Status.PAUSED,
                        unitImperial = imperial,
                    ),
                )
                delay(5_000)
            }
        }
    }

    override fun onDestroy() {
        locationTracker.stop()
        gpsStarted = false
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.vtrack.app.action.START_WORKOUT"
        const val ACTION_PAUSE = "com.vtrack.app.action.PAUSE_WORKOUT"
        const val ACTION_RESUME = "com.vtrack.app.action.RESUME_WORKOUT"
        const val ACTION_STOP = "com.vtrack.app.action.STOP_WORKOUT"

        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_ACTIVITY_TYPE = "activity_type"
        private const val EXTRA_INDOOR = "indoor"

        fun intent(context: Context): Intent =
            Intent(context, LiveWorkoutService::class.java)

        fun startIntent(
            context: Context,
            sessionId: String,
            activityTypeName: String,
            indoor: Boolean,
        ): Intent = intent(context)
            .setAction(ACTION_START)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .putExtra(EXTRA_ACTIVITY_TYPE, activityTypeName)
            .putExtra(EXTRA_INDOOR, indoor)
    }
}
