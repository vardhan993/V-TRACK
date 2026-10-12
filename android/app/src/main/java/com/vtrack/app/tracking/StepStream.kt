package com.vtrack.app.tracking

import android.content.Context
import com.vtrack.app.core.security.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class StepDelta(val steps: Int, val rawTotal: Long?, val at: Long)

/**
 * Single fan-out of hardware step events.
 *
 * There is exactly one sensor registration no matter how many consumers are active
 * (passive daily tracking service, live workout screen, …). That matters for two reasons:
 * battery, and correctness — if two consumers each wrote their own step deltas into the
 * daily aggregate, the day would double count.
 */
@Singleton
class StepStream @Inject constructor(
    private val tracker: StepSensorTracker,
    @ApplicationContext private val context: Context,
) {
    private val _deltas = MutableSharedFlow<StepDelta>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deltas: SharedFlow<StepDelta> = _deltas.asSharedFlow()

    private val lock = Any()
    private var refCount = 0
    private var registered = false

    /** Last raw sensor total, persisted across process death to keep deltas sane. */
    var lastRawTotal: Long?
        get() = lastRaw?.takeIf { it >= 0 }
        private set(value) {
            lastRaw = value ?: -1L
        }

    private var lastRaw: Long? = null

    val supported: Boolean get() = tracker.supported

    fun hasPermission(): Boolean = tracker.hasPermission(context)

    /** Idempotent add of a consumer; the sensor starts on the first one. */
    fun acquire(): Boolean {
        synchronized(lock) {
            refCount++
            if (registered) return true
            registered = tracker.start { delta, raw, at ->
                lastRawTotal = raw
                _deltas.tryEmit(StepDelta(delta, raw, at))
            }
            return registered
        }
    }

    fun release() {
        synchronized(lock) {
            refCount = (refCount - 1).coerceAtLeast(0)
            if (refCount == 0 && registered) {
                tracker.stop()
                registered = false
            }
        }
    }

    companion object {
        /** Raw counter value that the last accepted delta was derived from. */
        const val UNKNOWN = -1L
    }
}
