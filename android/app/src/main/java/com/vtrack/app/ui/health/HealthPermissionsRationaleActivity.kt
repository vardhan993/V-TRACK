package com.vtrack.app.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtrack.app.ui.components.VTrackCard
import com.vtrack.app.ui.theme.VTrackTheme

/**
 * Required by Google Play and Health Connect: a visible explanation of why V-Track reads
 * health data, shown before the permission dialog and reachable from the Health Connect
 * settings screen via `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`.
 *
 * V-Track only ever *reads* steps, distance, active calories and exercise sessions, and
 * only when the user turns the integration on.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VTrackTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "Why V-Track reads Health Connect",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        VTrackCard {
                            Text(
                                "V-Track can read steps, distance, active calories and exercise " +
                                    "sessions that other apps or wearables have already recorded, so " +
                                    "your dashboard reflects your whole day instead of only the time " +
                                    "your phone was with you.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "• Access is read-only. V-Track never writes to Health Connect.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "• Data is used only inside the app, on your device, to show activity, " +
                                    "charts and coaching suggestions.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "• Imported records are de-duplicated by their record id, so nothing is " +
                                    "counted twice.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "• You can revoke access at any time in Health Connect settings, and " +
                                    "turning the integration off inside V-Track stops imports immediately.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        VTrackCard {
                            Text(
                                "Reward eligibility",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "V-Coin rewards are based on activity recorded by this device's own " +
                                    "sensors. Imported Health Connect steps are shown for completeness and " +
                                    "are clearly separated from device-verified steps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "V-Coins are virtual credits with no monetary value.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
