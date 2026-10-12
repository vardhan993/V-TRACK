package com.vtrack.app.tracking

import android.content.Context
import androidx.core.content.ContextCompat
import com.vtrack.app.data.repository.ProfileRepository
import com.vtrack.app.data.repository.WorkoutRepository
import com.vtrack.app.domain.engine.CalorieCalculator
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.GpsPoint
import com.vtrack.app.domain.model.UserProfile
import com.vtrack.app.domain.model.WorkoutSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything the live workout UI needs; updated once per second. */
data class LiveWorkoutState(
    val sessionId: String? = null,
    val activityType: ActivityType = ActivityType.WALKING,
    val status: Status = Status.IDLE,
    val elapsedSeconds: Long = 0,
    val distanceMeters: Double = 0.0,
    val calories: Double = 0.0,
    val steps: Int = 0,
    val currentPaceSecPerKm: Double = 0.0,
    val averagePaceSecPerKm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val pointCount: Int = 0,
    val startedAt: Long = 0,
    val gpsActive: Boolean = false,
    val indoor: Boolean = false,
    val message: String? = null,
) {
    enum class Status { IDLE, RUNNING, PAUSED, FINISHED }

    val isActive: Boolean get() = status == Status.RUNNING || status == Status.PAUSED
}

/**
 * The live workout state machine.
 *
 * Owns elapsed time (excluding pauses), distance (GPS for outdoor sessions, stride-derived
 * for indoor ones), a rolling pace window, session step accumulation, and periodic
 * persistence so a killed process never loses a workout.
 *
 * Sensor registrations are shared with the rest of the app through [StepStream], which
 * guarantees the day's step total is never counted twice.
 */
@Singleton
class LiveWorkoutController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workoutRepository: WorkoutRepository,
    private val profileRepository: ProfileRepository,
    private val stepStream: StepStream,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(LiveWorkoutState())
    val state: StateFlow<LiveWorkoutState> = _state.asStateFlow()

    private var profile: UserProfile = UserProfile.empty()
    private var ticker: Job? = null
    private var accumulatedSeconds: Long = 0
    private var runStartedAt: Long = 0
    private var gpsDistance: Double = 0.0
    private var stepDistance: Double = 0.0
    private var lastPoint: GpsPoint? = null
    private val recentPoints = ArrayDeque<Pair<Long, Double>>()
    private var lastPersistAt: Long = 0
    private var boundToSteps = false

    init {
        // A single collector for the whole lifetime of the controller.
        scope.launch {
            stepStream.deltas.collect { delta ->
                val current = _state.value
                if (current.status == LiveWorkoutState.Status.RUNNING && delta.steps > 0) {
                    onSteps(delta.steps)
                }
            }
        }
    }

    suspend fun start(activityType: ActivityType, indoor: Boolean) {
        mutex.withLock {
            if (_state.value.isActive) return
            profile = profileRepository.currentProfile()
            val now = System.currentTimeMillis()
            val effectiveIndoor = indoor || !activityType.usesGps
            val session = workoutRepository.startSession(
                activityType = activityType,
                indoor = effectiveIndoor,
                now = now,
            )
            accumulatedSeconds = 0
            runStartedAt = now
            gpsDistance = 0.0
            stepDistance = 0.0
            lastPoint = null
            recentPoints.clear()
            lastPersistAt = now
            _state.value = LiveWorkoutState(
                sessionId = session.id,
                activityType = activityType,
                status = LiveWorkoutState.Status.RUNNING,
                startedAt = now,
                indoor = effectiveIndoor,
                message = if (effectiveIndoor) {
                    "Indoor session — distance comes from your step count."
                } else {
                    null
                },
            )
            if (!boundToSteps) {
                stepStream.acquire()
                boundToSteps = true
            }
            ContextCompat.startForegroundService(
                context,
                LiveWorkoutService.startIntent(
                    context = context,
                    sessionId = session.id,
                    activityTypeName = activityType.name,
                    indoor = effectiveIndoor,
                ),
            )
            ticker?.cancel()
            ticker = scope.launch {
                while (_state.value.isActive) {
                    delay(1_000)
                    tick()
                }
            }
        }
    }

    fun pause() {
        val current = _state.value
        if (current.status != LiveWorkoutState.Status.RUNNING) return
        accumulatedSeconds += (System.currentTimeMillis() - runStartedAt) / 1000
        _state.value = current.copy(
            status = LiveWorkoutState.Status.PAUSED,
            elapsedSeconds = accumulatedSeconds,
        )
        persistProgress()
        notifyService(LiveWorkoutService.ACTION_PAUSE)
    }

    fun resume() {
        val current = _state.value
        if (current.status != LiveWorkoutState.Status.PAUSED) return
        runStartedAt = System.currentTimeMillis()
        lastPoint = null
        recentPoints.clear()
        _state.value = current.copy(status = LiveWorkoutState.Status.RUNNING, message = null)
        notifyService(LiveWorkoutService.ACTION_RESUME)
    }

    /** Stops and finalizes the session, returning the stored record. */
    suspend fun stop(): WorkoutSession? {
        val current = _state.value
        if (!current.isActive) return null
        if (current.status == LiveWorkoutState.Status.RUNNING) {
            accumulatedSeconds += (System.currentTimeMillis() - runStartedAt) / 1000
        }
        val sessionId = current.sessionId ?: return null
        _state.value = current.copy(
            status = LiveWorkoutState.Status.FINISHED,
            elapsedSeconds = accumulatedSeconds,
        )
        persistProgress()
        val finished = workoutRepository.finishSession(sessionId)
        teardown()
        _state.value = LiveWorkoutState()
        return finished
    }

    /** Abandons the session without keeping a record. */
    suspend fun discard() {
        val sessionId = _state.value.sessionId ?: return
        teardown()
        workoutRepository.deleteSession(sessionId)
        _state.value = LiveWorkoutState()
    }

    /** Called by the service for each accepted GPS fix. */
    fun onPoint(point: GpsPoint) {
        val current = _state.value
        if (current.status != LiveWorkoutState.Status.RUNNING) return

        val previous = lastPoint
        if (previous != null) {
            val increment = haversine(
                previous.latitude, previous.longitude,
                point.latitude, point.longitude,
            )
            // Reject sub-metre jitter so a stationary phone accumulates no distance.
            if (increment >= 1.0) {
                gpsDistance += increment
                recentPoints.addLast(point.recordedAt to gpsDistance)
            }
        }
        lastPoint = point
        while (recentPoints.isNotEmpty() && point.recordedAt - recentPoints.first().first > 60_000) {
            recentPoints.removeFirst()
        }
        _state.value = _state.value.copy(pointCount = _state.value.pointCount + 1)
        scope.launch {
            current.sessionId?.let { workoutRepository.appendPoint(it, point) }
        }
        recompute()
    }

    fun onSteps(delta: Int) {
        val current = _state.value
        if (current.status != LiveWorkoutState.Status.RUNNING || delta <= 0) return
        stepDistance += CalorieCalculator.strideMeters(profile.heightCm, current.activityType) * delta
        recompute(steps = current.steps + delta)
    }

    fun onGpsAvailability(active: Boolean) {
        _state.value = _state.value.copy(gpsActive = active)
    }

    /** Rebinds to a session that was still open when the app was killed. */
    suspend fun restoreIfSessionOpen() {
        if (_state.value.isActive) return
        val open = workoutRepository.observeActiveSession().firstOrNull() ?: return
        profile = profileRepository.currentProfile()
        accumulatedSeconds = open.durationSeconds
        runStartedAt = System.currentTimeMillis()
        val points = workoutRepository.getPoints(open.id)
        gpsDistance = if (points.size >= 2) polyline(points) else open.distanceMeters
        lastPoint = points.lastOrNull()
        stepDistance = CalorieCalculator.distanceFromSteps(open.steps, profile.heightCm, open.activityType)
        _state.value = LiveWorkoutState(
            sessionId = open.id,
            activityType = open.activityType,
            status = LiveWorkoutState.Status.PAUSED,
            elapsedSeconds = open.durationSeconds,
            distanceMeters = open.distanceMeters,
            calories = open.calories,
            steps = open.steps,
            startedAt = open.startedAt,
            indoor = open.indoor,
            pointCount = points.size,
            message = "Recovered an unfinished session — resume or stop it.",
        )
    }

    /* ------------------------------------------------------------- internals */

    private fun teardown() {
        ticker?.cancel()
        ticker = null
        if (boundToSteps) {
            stepStream.release()
            boundToSteps = false
        }
        runCatching { context.stopService(LiveWorkoutService.intent(context)) }
    }

    private fun tick() {
        if (!_state.value.isActive) return
        if (_state.value.status == LiveWorkoutState.Status.RUNNING) {
            recompute()
            if (System.currentTimeMillis() - lastPersistAt > 15_000) persistProgress()
        }
    }

    private fun elapsedNow(): Long {
        val current = _state.value
        return if (current.status == LiveWorkoutState.Status.RUNNING) {
            accumulatedSeconds + (System.currentTimeMillis() - runStartedAt) / 1000
        } else {
            accumulatedSeconds
        }
    }

    private fun recompute(steps: Int = _state.value.steps) {
        val current = _state.value
        val activity = current.activityType
        val elapsed = elapsedNow()
        val distance = when {
            current.indoor || !activity.usesGps -> stepDistance
            gpsDistance > 50 -> gpsDistance
            else -> stepDistance
        }
        val weight = CalorieCalculator.effectiveWeight(profile.weightKg)
        val metCalories = CalorieCalculator.fromActivity(activity, weight, elapsed)
        val stepCalories = if (activity.countsSteps) {
            CalorieCalculator.fromSteps(steps, profile.weightKg, profile.heightCm, activity)
        } else {
            0.0
        }
        val averagePace = if (distance > 50 && elapsed > 0) elapsed / (distance / 1000.0) else 0.0

        _state.value = current.copy(
            elapsedSeconds = elapsed,
            distanceMeters = distance,
            calories = maxOf(metCalories, stepCalories),
            steps = steps,
            averagePaceSecPerKm = averagePace,
            currentPaceSecPerKm = windowPace(elapsed, distance, averagePace),
            speedKmh = if (averagePace > 0) 3600.0 / averagePace else 0.0,
        )
    }

    /** Pace over the last ~60 seconds only, which is what a runner actually wants to see. */
    private fun windowPace(elapsed: Long, distance: Double, fallback: Double): Double {
        if (recentPoints.size < 2) return fallback
        val (startedAt, startedDistance) = recentPoints.first()
        val windowSeconds = (elapsed * 1000 - (startedAt - _state.value.startedAt)).coerceAtLeast(0) / 1000.0
        val windowDistance = distance - startedDistance
        if (windowSeconds <= 1 || windowDistance < 5) return fallback
        return windowSeconds / (windowDistance / 1000.0)
    }

    private fun persistProgress() {
        val current = _state.value
        val sessionId = current.sessionId ?: return
        lastPersistAt = System.currentTimeMillis()
        scope.launch {
            val existing = workoutRepository.getSession(sessionId) ?: return@launch
            workoutRepository.saveProgress(
                existing.copy(
                    durationSeconds = current.elapsedSeconds,
                    distanceMeters = current.distanceMeters,
                    calories = current.calories,
                    steps = current.steps,
                    avgPaceSecPerKm = current.averagePaceSecPerKm,
                    avgSpeedKmh = current.speedKmh,
                ),
            )
        }
    }

    private fun notifyService(action: String) {
        runCatching { context.startService(LiveWorkoutService.intent(context).setAction(action)) }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun polyline(points: List<GpsPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversine(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude,
            )
        }
        return total
    }
}
