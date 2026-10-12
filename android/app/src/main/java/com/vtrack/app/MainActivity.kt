package com.vtrack.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vtrack.app.data.repository.SyncEngine
import com.vtrack.app.tracking.StepTrackingService
import com.vtrack.app.tracking.hasActivityRecognitionPermission
import com.vtrack.app.ui.navigation.VTrackApp
import com.vtrack.app.ui.theme.VTrackTheme
import com.vtrack.app.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app's only activity. Compose hosts every screen; the activity itself handles
 * edge-to-edge setup, the permission prompts that make tracking possible, restarting
 * passive tracking, and a foreground sync attempt.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var syncEngine: SyncEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACTIVITY_RECOGNITION] == true) {
            StepTrackingService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestStartupPermissions()

        setContent {
            // The theme follows the system light/dark setting.
            VTrackTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    VTrackApp()
                }
            }
        }

        if (hasActivityRecognitionPermission()) {
            StepTrackingService.start(this)
        }
        workScheduler.scheduleAll()
        lifecycleScope.launch { runCatching { syncEngine.sync() } }
    }

    /**
     * Notification permission is required before a foreground service may show its
     * notification, and activity recognition is required for hardware step counting.
     * Both are optional: refusing them leaves the app usable with manual logging.
     */
    private fun requestStartupPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACTIVITY_RECOGNITION,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }.toTypedArray()
        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
