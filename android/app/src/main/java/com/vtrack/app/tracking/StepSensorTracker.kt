package com.vtrack.app.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device's hardware step counter.
 *
 * Hardware reality this class handles honestly:
 *  - `TYPE_STEP_COUNTER` reports steps *since boot*, so we store the last raw reading and
 *    emit only the difference. A reboot (or sensor reset) makes the value drop, and the
 *    tracker detects that and rebases instead of reporting a huge negative delta.
 *  - `TYPE_STEP_DETECTOR` fires an event per detected step; it is used only as a fallback
 *    when no counter exists, and each event counts as exactly one step.
 *  - If neither sensor exists (or the hardware returns nothing), [supported] stays false
 *    and the UI tells the user that step counting is unavailable on this device rather
 *    than inventing numbers.
 */
@Singleton
class StepSensorTracker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val counterSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val detectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var listener: SensorEventListener? = null
    private var lastRawTotal: Float? = null

    val supported: Boolean get() = counterSensor != null || detectorSensor != null
    val usesCounter: Boolean get() = counterSensor != null

    /**
     * Starts listening. [onSteps] receives the number of steps detected since the previous
     * callback (never negative) and the raw sensor total when available.
     */
    fun start(onSteps: (delta: Int, rawTotal: Long?, at: Long) -> Unit): Boolean {
        val manager = sensorManager ?: return false
        stop()

        val sensor = counterSensor ?: detectorSensor ?: return false
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                if (e.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                    val raw = e.values.firstOrNull() ?: return
                    val previous = lastRawTotal
                    lastRawTotal = raw
                    if (previous == null || raw < previous) {
                        // First reading after registration or a reboot: nothing to credit yet.
                        return
                    }
                    val delta = (raw - previous).toInt()
                    if (delta > 0) onSteps(delta, raw.toLong(), e.timestamp / 1_000_000)
                } else if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                    // Each event is one step.
                    onSteps(1, null, e.timestamp / 1_000_000)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = manager.registerListener(
            sensorListener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
        )
        if (registered) listener = sensorListener
        return registered
    }

    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        lastRawTotal = null
    }

    fun hasPermission(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            context.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
