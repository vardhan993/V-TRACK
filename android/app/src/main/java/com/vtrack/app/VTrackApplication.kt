package com.vtrack.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vtrack.app.data.repository.ExerciseRepository
import com.vtrack.app.data.repository.RewardsRepository
import com.vtrack.app.tracking.Notifications
import com.vtrack.app.tracking.StepTrackingService
import com.vtrack.app.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup work is deliberately small and non-blocking: notification channels, the worker
 * factory, the bundled exercise/achievement catalogs, background schedules, and passive
 * step tracking when the user has already granted the permission. Everything that needs
 * the network happens in WorkManager, not here.
 */
@HiltAndroidApp
class VTrackApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var exerciseRepository: ExerciseRepository
    @Inject lateinit var rewardsRepository: RewardsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)

        applicationScope.launch {
            runCatching { exerciseRepository.ensureSeeded() }
            runCatching { rewardsRepository.seedAchievementCatalog() }
        }

        workScheduler.scheduleAll()

        if (hasActivityRecognitionPermission()) {
            StepTrackingService.start(this)
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
