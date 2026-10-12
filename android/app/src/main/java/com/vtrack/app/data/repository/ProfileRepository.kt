package com.vtrack.app.data.repository

import com.vtrack.app.core.error.VTrackError
import com.vtrack.app.core.error.mapToVTrackError
import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.core.util.UnitSystem
import com.vtrack.app.data.local.Mappers.toDomain
import com.vtrack.app.data.local.Mappers.toEntity
import com.vtrack.app.data.local.VTrackDatabase
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.local.entity.GoalEntity
import com.vtrack.app.data.local.entity.UserProfileEntity
import com.vtrack.app.data.remote.RemoteMappers.toProfileFields
import com.vtrack.app.data.remote.VTrackApi
import com.vtrack.app.data.remote.dto.UpdateProfileRequest
import com.vtrack.app.domain.model.ActivityType
import com.vtrack.app.domain.model.FitnessLevel
import com.vtrack.app.domain.model.PrimaryGoal
import com.vtrack.app.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Partial update carrier: null means "leave unchanged". */
data class ProfileUpdate(
    val displayName: String? = null,
    val avatarEmoji: String? = null,
    val birthYear: Int? = null,
    val sex: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val fitnessLevel: FitnessLevel? = null,
    val primaryGoal: PrimaryGoal? = null,
    val preferredActivities: List<ActivityType>? = null,
    val weeklyWorkoutTarget: Int? = null,
    val unitSystem: UnitSystem? = null,
    val stepGoal: Int? = null,
    val onboardingComplete: Boolean? = null,
    val healthConnectEnabled: Boolean? = null,
    val shareRoutes: Boolean? = null,
    val shareActivityWithCoach: Boolean? = null,
)

data class GoalSnapshot(
    val stepGoal: Int,
    val weeklyWorkoutTarget: Int,
    val distanceTargetMeters: Double,
    val activeMinutesTarget: Int,
)

/**
 * Profile and goals. Writes are local-first and pushed to the API when online; the
 * outbox entry means a change made offline is never lost. Every field the user can set
 * here is optional — the app works with nothing but an email address.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val api: VTrackApi,
    private val database: VTrackDatabase,
    private val networkMonitor: NetworkMonitor,
    private val sessionStore: SessionStore,
    private val json: Json,
) {

    fun observeProfile(): Flow<UserProfile> = profileDao.observeProfile().map { entity ->
        entity?.toDomain() ?: UserProfile.empty()
    }

    suspend fun currentProfile(): UserProfile =
        profileDao.getProfile()?.toDomain() ?: UserProfile.empty()

    fun observeGoals(): Flow<GoalSnapshot> = profileDao.observeGoals().map { goals ->
        val byType = goals.associateBy { it.type }
        GoalSnapshot(
            stepGoal = byType["STEPS"]?.targetValue?.toInt() ?: 8_000,
            weeklyWorkoutTarget = byType["WEEKLY_WORKOUTS"]?.targetValue?.toInt() ?: 3,
            distanceTargetMeters = byType["DISTANCE_METERS"]?.targetValue ?: 0.0,
            activeMinutesTarget = byType["ACTIVE_MINUTES"]?.targetValue?.toInt() ?: 30,
        )
    }

    /**
     * Creates the local profile row from the session cache if the database is empty
     * (fresh install, restored device, or a database that was wiped by the user).
     */
    suspend fun ensureLocalProfile(): UserProfile {
        val existing = profileDao.getProfile()
        if (existing != null) return existing.toDomain()
        val id = sessionStore.userId() ?: return UserProfile.empty()
        val profile = UserProfile.empty().copy(
            id = id,
            email = sessionStore.cachedEmail().orEmpty(),
            displayName = sessionStore.cachedDisplayName().orEmpty(),
        )
        profileDao.upsertProfile(profile.toEntity())
        return profile
    }

    suspend fun updateProfile(update: ProfileUpdate): Result<UserProfile> = runCatching {
        val existing = ensureLocalProfile()
        val merged = existing.copy(
            displayName = update.displayName ?: existing.displayName,
            avatarEmoji = update.avatarEmoji ?: existing.avatarEmoji,
            birthYear = update.birthYear ?: existing.birthYear,
            sex = update.sex ?: existing.sex,
            heightCm = update.heightCm ?: existing.heightCm,
            weightKg = update.weightKg ?: existing.weightKg,
            fitnessLevel = update.fitnessLevel ?: existing.fitnessLevel,
            primaryGoal = update.primaryGoal ?: existing.primaryGoal,
            preferredActivities = update.preferredActivities ?: existing.preferredActivities,
            weeklyWorkoutTarget = update.weeklyWorkoutTarget ?: existing.weeklyWorkoutTarget,
            unitSystem = update.unitSystem ?: existing.unitSystem,
            stepGoal = update.stepGoal ?: existing.stepGoal,
            onboardingComplete = update.onboardingComplete ?: existing.onboardingComplete,
            healthConnectEnabled = update.healthConnectEnabled ?: existing.healthConnectEnabled,
            shareRoutes = update.shareRoutes ?: existing.shareRoutes,
            shareActivityWithCoach = update.shareActivityWithCoach ?: existing.shareActivityWithCoach,
        )
        profileDao.upsertProfile(merged.toEntity(profileDao.getProfile()))
        syncGoals(merged)
        pushToServer(update)
        merged
    }.mapToVTrackError()

    suspend fun setStepGoal(steps: Int): Result<Unit> = runCatching {
        val clamped = steps.coerceIn(1_000, 50_000)
        profileDao.updateStepGoal(clamped, System.currentTimeMillis())
        profileDao.upsertGoal(
            GoalEntity(
                type = "STEPS",
                targetValue = clamped.toDouble(),
                period = "DAILY",
                updatedAt = System.currentTimeMillis(),
                syncedAt = null,
            ),
        )
        pushToServer(ProfileUpdate(stepGoal = clamped))
    }.mapToVTrackError()

    suspend fun completeOnboarding(profile: UserProfile): Result<UserProfile> =
        updateProfile(
            ProfileUpdate(
                displayName = profile.displayName,
                primaryGoal = profile.primaryGoal,
                fitnessLevel = profile.fitnessLevel,
                preferredActivities = profile.preferredActivities,
                weeklyWorkoutTarget = profile.weeklyWorkoutTarget,
                unitSystem = profile.unitSystem,
                stepGoal = profile.stepGoal,
                heightCm = profile.heightCm,
                weightKg = profile.weightKg,
                birthYear = profile.birthYear,
                sex = profile.sex,
                onboardingComplete = true,
            ),
        )

    suspend fun setHealthConnectEnabled(enabled: Boolean): Result<Unit> = runCatching {
        profileDao.updateHealthConnect(enabled, System.currentTimeMillis())
        pushToServer(ProfileUpdate(healthConnectEnabled = enabled))
    }.mapToVTrackError()

    suspend fun refreshFromServer(): Result<Unit> = runCatching {
        if (!networkMonitor.isCurrentlyOnline()) throw VTrackError.Offline()
        if (!sessionStore.isSignedIn()) return@runCatching
        val dto = api.me()
        val fields = dto.toProfileFields()
        val current = profileDao.getProfile()
        val merged = UserProfile(
            id = fields.id,
            email = fields.email,
            displayName = fields.displayName,
            avatarEmoji = fields.avatarEmoji ?: current?.avatarEmoji,
            avatarUrl = fields.avatarUrl ?: current?.avatarUrl,
            birthYear = fields.birthYear ?: current?.birthYear,
            sex = fields.sex ?: current?.sex,
            heightCm = fields.heightCm ?: current?.heightCm,
            weightKg = fields.weightKg ?: current?.weightKg,
            fitnessLevel = FitnessLevel.fromStorage(fields.fitnessLevel),
            primaryGoal = PrimaryGoal.fromStorage(fields.primaryGoal),
            preferredActivities = current?.toDomain()?.preferredActivities ?: emptyList(),
            weeklyWorkoutTarget = fields.weeklyWorkoutTarget,
            unitSystem = if (fields.unitSystem.equals("IMPERIAL", true)) UnitSystem.IMPERIAL else UnitSystem.METRIC,
            stepGoal = fields.stepGoal,
            onboardingComplete = fields.onboardingComplete,
            healthConnectEnabled = fields.healthConnectEnabled,
            shareRoutes = fields.shareRoutes,
            shareActivityWithCoach = fields.shareActivityWithCoach,
        )
        profileDao.upsertProfile(merged.toEntity(current))
        syncGoals(merged)
    }.mapToVTrackError()

    /** Data export: the user's own data as a JSON document they can keep. */
    suspend fun exportData(): Result<String> = runCatching {
        val profile = ensureLocalProfile()
        val payload = buildString {
            append("{\n")
            append("  \"exportedAt\": ${System.currentTimeMillis()},\n")
            append("  \"profile\": ${json.encodeToString(UserProfileExport.serializer(), profile.toExport())},\n")
            append("  \"note\": \"Activity, workouts and coin history are available from the API export endpoint when signed in.\"\n")
            append("}")
        }
        payload
    }.mapToVTrackError()

    /**
     * Account deletion: erase server-side records first (so nothing is left behind), then
     * wipe local tables and credentials. If the network is unavailable the deletion is
     * refused rather than silently pretending to have completed.
     */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        if (!sessionStore.isSignedIn()) {
            deleteLocalData()
            return@runCatching
        }
        if (!networkMonitor.isCurrentlyOnline()) {
            throw VTrackError.Offline()
        }
        api.deleteAccount()
        deleteLocalData()
    }.mapToVTrackError()

    suspend fun deleteLocalData() {
        database.clearAllTables()
        sessionStore.clear()
    }

    private suspend fun pushToServer(update: ProfileUpdate) {
        if (!networkMonitor.isCurrentlyOnline() || !sessionStore.isSignedIn()) return
        runCatching {
            api.updateProfile(
                UpdateProfileRequest(
                    displayName = update.displayName,
                    avatarEmoji = update.avatarEmoji,
                    birthYear = update.birthYear,
                    sex = update.sex,
                    heightCm = update.heightCm,
                    weightKg = update.weightKg,
                    fitnessLevel = update.fitnessLevel?.name,
                    primaryGoal = update.primaryGoal?.name,
                    preferredActivities = update.preferredActivities?.map { it.name },
                    weeklyWorkoutTarget = update.weeklyWorkoutTarget,
                    unitSystem = update.unitSystem?.name,
                    stepGoal = update.stepGoal,
                    onboardingComplete = update.onboardingComplete,
                    healthConnectEnabled = update.healthConnectEnabled,
                    shareRoutes = update.shareRoutes,
                    shareActivityWithCoach = update.shareActivityWithCoach,
                ),
            )
        }
    }

    private suspend fun syncGoals(profile: UserProfile) {
        val now = System.currentTimeMillis()
        profileDao.upsertGoals(
            listOf(
                GoalEntity("STEPS", profile.stepGoal.toDouble(), "DAILY", now, null),
                GoalEntity("WEEKLY_WORKOUTS", profile.weeklyWorkoutTarget.toDouble(), "WEEKLY", now, null),
                GoalEntity("ACTIVE_MINUTES", 30.0, "DAILY", now, null),
            ),
        )
    }

    @kotlinx.serialization.Serializable
    private data class UserProfileExport(
        val id: String,
        val email: String,
        val displayName: String,
        val fitnessLevel: String,
        val primaryGoal: String,
        val unitSystem: String,
        val stepGoal: Int,
        val weeklyWorkoutTarget: Int,
        val heightCm: Double? = null,
        val weightKg: Double? = null,
        val birthYear: Int? = null,
        val sex: String? = null,
        val preferredActivities: List<String> = emptyList(),
        val shareRoutes: Boolean = false,
        val shareActivityWithCoach: Boolean = true,
        val healthConnectEnabled: Boolean = false,
    ) {
        companion object
    }

    private fun UserProfile.toExport() = UserProfileExport(
        id = id,
        email = email,
        displayName = displayName,
        fitnessLevel = fitnessLevel.name,
        primaryGoal = primaryGoal.name,
        unitSystem = unitSystem.name,
        stepGoal = stepGoal,
        weeklyWorkoutTarget = weeklyWorkoutTarget,
        heightCm = heightCm,
        weightKg = weightKg,
        birthYear = birthYear,
        sex = sex,
        preferredActivities = preferredActivities.map { it.name },
        shareRoutes = shareRoutes,
        shareActivityWithCoach = shareActivityWithCoach,
        healthConnectEnabled = healthConnectEnabled,
    )
}
