package com.vtrack.app.tracking

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vtrack.app.data.repository.ActivityRepository
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.domain.model.UserProfile
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
 * Always-on step counting.
 *
 * This is the *only* writer of device step samples into the database, which is why the
 * daily total can never double count. It runs as a foreground service of type `health`
 * with a silent, ongoing notification (Android requires a visible notification for
 * background sensors) and stops itself immediately if the step permission is missing.
 *
 * Battery notes: the hardware step counter is a low-power sensor and we read it at
 * SENSOR_DELAY_NORMAL, writing at most one row per hour bucket.
 */
@AndroidEntryPoint
class StepTrackingService : Service() {

    @Inject lateinit var stepStream: StepStream
    @Inject lateinit var activityRepository: ActivityRepository
    @Inject lateinit var profileRepository: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null
    private var profile: UserProfile = UserProfile.empty()
    private var todaySteps: Int = 0

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!stepStream.hasPermission() || !stepStream.supported) {
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            Notifications.ID_STEP_SERVICE,
            Notifications.stepTracking(this, todaySteps),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                0
            },
        )

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (notificationJob == null) {
            scope.launch {
                profile = profileRepository.currentProfile()
                activityRepository.observeToday(profile.stepGoal).collect { day ->
                    todaySteps = day.steps
                }
            }
            stepStream.acquire()
            notificationJob = scope.launch {
                while (true) {
                    delay(60_000)
                    Notifications.notify(
                        this@StepTrackingService,
                        Notifications.ID_STEP_SERVICE,
                        Notifications.stepTracking(this@StepTrackingService, todaySteps),
                    )
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stepStream.release()
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.vtrack.app.action.START_STEP_TRACKING"
        const val ACTION_STOP = "com.vtrack.app.action.STOP_STEP_TRACKING"

        fun start(context: Context) {
            val intent = Intent(context, StepTrackingService::class.java).setAction(ACTION_START)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, StepTrackingService::class.java)) }
        }
    }
}
