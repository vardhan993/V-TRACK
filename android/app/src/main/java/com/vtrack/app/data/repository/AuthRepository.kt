package com.vtrack.app.data.repository

import com.vtrack.app.core.error.mapToVTrackError
import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.core.util.NetworkMonitor
import com.vtrack.app.data.local.Mappers.toEntity
import com.vtrack.app.data.local.Mappers.toDomain
import com.vtrack.app.data.local.dao.ProfileDao
import com.vtrack.app.data.remote.RemoteMappers.toProfileFields
import com.vtrack.app.data.remote.VTrackApi
import com.vtrack.app.data.remote.dto.AuthResponse
import com.vtrack.app.data.remote.dto.LoginRequest
import com.vtrack.app.data.remote.dto.RefreshRequest
import com.vtrack.app.data.remote.dto.RegisterRequest
import com.vtrack.app.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns sign-up, sign-in, session refresh and sign-out.
 *
 * Accounts are V-Track's own: the Worker stores PBKDF2-HMAC-SHA256 password hashes and
 * issues short-lived access tokens plus rotating refresh tokens. No Firebase, no
 * Supabase, no third-party identity provider and no client secret in the app.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: VTrackApi,
    private val sessionStore: SessionStore,
    private val profileDao: ProfileDao,
    private val networkMonitor: NetworkMonitor,
) {
    private val _authState = MutableStateFlow(sessionStore.isSignedIn())
    val authState: Flow<Boolean> = _authState.asStateFlow()

    fun isSignedIn(): Boolean = sessionStore.isSignedIn()

    suspend fun register(email: String, password: String, displayName: String): Result<UserProfile> =
        runCatching {
            requireOnline()
            val auth = api.register(
                RegisterRequest(
                    email = email.trim().lowercase(),
                    password = password,
                    displayName = displayName.trim(),
                ),
            )
            persist(auth)
            val previous = profileDao.getProfile()
            val profile = mergeProfile(auth.user.toProfileFields(), previous)
            profileDao.upsertProfile(profile.toEntity(previous))
            profile
        }.mapToVTrackError()

    suspend fun login(email: String, password: String): Result<UserProfile> =
        runCatching {
            requireOnline()
            val auth = api.login(LoginRequest(email.trim().lowercase(), password))
            persist(auth)
            val cached = profileDao.getProfile()
            // The server copy is authoritative for account fields; the local copy only
            // fills in optional details the user has not pushed yet.
            val merged = mergeProfile(auth.user.toProfileFields(), cached)
            profileDao.upsertProfile(merged.toEntity(cached))
            merged
        }.mapToVTrackError()

    /** Builds the local profile from the server's account data plus any unsynced local detail. */
    private fun mergeProfile(
        fields: com.vtrack.app.data.remote.RemoteMappers.ProfileFields,
        local: com.vtrack.app.data.local.entity.UserProfileEntity?,
    ): UserProfile = UserProfile(
        id = fields.id,
        email = fields.email,
        displayName = fields.displayName,
        avatarEmoji = fields.avatarEmoji ?: local?.avatarEmoji,
        avatarUrl = fields.avatarUrl ?: local?.avatarUrl,
        birthYear = fields.birthYear ?: local?.birthYear,
        sex = fields.sex ?: local?.sex,
        heightCm = fields.heightCm ?: local?.heightCm,
        weightKg = fields.weightKg ?: local?.weightKg,
        fitnessLevel = com.vtrack.app.domain.model.FitnessLevel.fromStorage(fields.fitnessLevel),
        primaryGoal = com.vtrack.app.domain.model.PrimaryGoal.fromStorage(fields.primaryGoal),
        preferredActivities = local?.toDomain()?.preferredActivities ?: emptyList(),
        weeklyWorkoutTarget = fields.weeklyWorkoutTarget,
        unitSystem = if (fields.unitSystem.equals("IMPERIAL", true)) {
            com.vtrack.app.core.util.UnitSystem.IMPERIAL
        } else {
            com.vtrack.app.core.util.UnitSystem.METRIC
        },
        stepGoal = fields.stepGoal,
        onboardingComplete = fields.onboardingComplete,
        healthConnectEnabled = fields.healthConnectEnabled,
        shareRoutes = fields.shareRoutes,
        shareActivityWithCoach = fields.shareActivityWithCoach,
    )

    /** Explicit refresh; also used when the app resumes and the token is close to expiry. */
    suspend fun refreshSession(): Result<Unit> = runCatching {
        val refreshToken = sessionStore.refreshToken() ?: error("No refresh token")
        val auth = api.refresh(RefreshRequest(refreshToken))
        persist(auth)
    }.mapToVTrackError()

    /**
     * Sign-out revokes the refresh token server-side *and* clears local credentials, so a
     * stolen token cannot be replayed. Local activity data is kept (it belongs to the user
     * and is still theirs after signing back in).
     */
    suspend fun logout(): Result<Unit> = runCatching {
        val refreshToken = sessionStore.refreshToken()
        if (refreshToken != null && networkMonitor.isCurrentlyOnline()) {
            runCatching { api.logout(RefreshRequest(refreshToken)) }
        }
        sessionStore.clear()
    }.mapToVTrackError()

    private fun persist(auth: AuthResponse) {
        sessionStore.save(
            SessionStore.Tokens(
                accessToken = auth.accessToken,
                refreshToken = auth.refreshToken,
                expiresAtEpochSeconds = Instant.now().epochSecond + auth.expiresIn,
                userId = auth.user.id,
                email = auth.user.email,
                displayName = auth.user.displayName,
            ),
        )
        _authState.value = true
    }

    private fun requireOnline() {
        if (!networkMonitor.isCurrentlyOnline()) {
            throw com.vtrack.app.core.error.VTrackError.Offline()
        }
    }
}
