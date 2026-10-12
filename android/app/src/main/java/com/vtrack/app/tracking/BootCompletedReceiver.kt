package com.vtrack.app.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vtrack.app.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-arms background work after a reboot or app update.
 *
 * Android does not keep foreground services or workers alive across a reboot, and the
 * hardware step counter resets on boot — so this receiver restarts passive tracking (only
 * if the user already granted the permission) and re-schedules the periodic sync,
 * rollup and reminder workers.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (context.hasActivityRecognitionPermission()) {
                    StepTrackingService.start(context)
                }
                workScheduler.scheduleAll()
            }
        }
    }
}

internal fun Context.hasActivityRecognitionPermission(): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
