package com.vtrack.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vtrack.app.MainActivity
import com.vtrack.app.R
import com.vtrack.app.core.util.Formatters

/** Single place where channels and notification content are defined. */
object Notifications {

    const val CHANNEL_TRACKING = "vtrack.tracking"
    const val CHANNEL_REMINDERS = "vtrack.reminders"
    const val CHANNEL_SYNC = "vtrack.sync"

    const val ID_LIVE_WORKOUT = 1001
    const val ID_STEP_SERVICE = 1002
    const val ID_SYNC = 1003
    const val ID_REMINDER = 1004

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                context.getString(R.string.channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_tracking_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminder_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_reminder_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = context.getString(R.string.channel_sync_desc) },
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, LiveWorkoutService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Live workout notification with pause/resume and stop actions. */
    fun liveWorkout(
        context: Context,
        activityLabel: String,
        elapsedSeconds: Long,
        distanceMeters: Double,
        calories: Double,
        paused: Boolean,
        unitImperial: Boolean,
    ): Notification {
        val units = if (unitImperial) {
            com.vtrack.app.core.util.UnitSystem.IMPERIAL
        } else {
            com.vtrack.app.core.util.UnitSystem.METRIC
        }
        val content = buildString {
            append(Formatters.duration(elapsedSeconds))
            append(" • ")
            append(Formatters.distance(distanceMeters, units))
            append(" • ")
            append(Formatters.calories(calories))
            append(" kcal")
        }
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("$activityLabel ${if (paused) "(paused)" else "in progress"}")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                NotificationCompat.Action(
                    0,
                    if (paused) "Resume" else "Pause",
                    serviceAction(
                        context,
                        if (paused) LiveWorkoutService.ACTION_RESUME else LiveWorkoutService.ACTION_PAUSE,
                        1,
                    ),
                ),
            )
            .addAction(
                NotificationCompat.Action(
                    0,
                    "Stop",
                    serviceAction(context, LiveWorkoutService.ACTION_STOP, 2),
                ),
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Low-importance always-on notification for passive step tracking. */
    fun stepTracking(context: Context, todaySteps: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle(context.getString(R.string.notif_steps_title))
            .setContentText("${Formatters.steps(todaySteps)} steps today")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context))
            .build()

    fun goalReminder(context: Context, steps: Int, goal: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("Keep your streak alive")
            .setContentText(
                "${Formatters.steps(steps)} of ${Formatters.steps(goal)} steps — a short walk would close the gap.",
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()

    fun syncStatus(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("V-Track sync")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    fun notify(context: Context, id: Int, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
