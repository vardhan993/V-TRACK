package com.vtrack.app.core.security

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Signed-in state changes the UI must react to (e.g. refresh token revoked server-side). */
sealed interface SessionEvent {
    data object SignedIn : SessionEvent
    data object SignedOut : SessionEvent
    data object SessionExpired : SessionEvent
}

/**
 * Owns the credential lifecycle. Tokens live only in [SecureStore] (Keystore-encrypted);
 * nothing here is ever logged, and the access token is short-lived so a leaked file is
 * of limited value. The refresh token is rotated on every use by the server.
 */
@Singleton
class SessionStore @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    fun save(urlSafeTokens: Tokens) {
        secureStore.putString(SecureStore.KEY_ACCESS_TOKEN, urlSafeTokens.accessToken)
        secureStore.putString(SecureStore.KEY_REFRESH_TOKEN, urlSafeTokens.refreshToken)
        secureStore.putString(SecureStore.KEY_ACCESS_EXPIRES_AT, urlSafeTokens.expiresAtEpochSeconds.toString())
        secureStore.putString(SecureStore.KEY_USER_ID, urlSafeTokens.userId)
        urlSafeTokens.email?.let { secureStore.putString(SecureStore.KEY_USER_EMAIL, it) }
        urlSafeTokens.displayName?.let { secureStore.putString(SecureStore.KEY_USER_NAME, it) }
        _events.tryEmit(SessionEvent.SignedIn)
    }

    fun clear(reason: SessionEvent = SessionEvent.SignedOut) {
        secureStore.clear()
        _events.tryEmit(reason)
    }

    fun isSignedIn(): Boolean =
        !secureStore.getString(SecureStore.KEY_REFRESH_TOKEN).isNullOrBlank()

    fun accessToken(): String? = secureStore.getString(SecureStore.KEY_ACCESS_TOKEN)

    fun refreshToken(): String? = secureStore.getString(SecureStore.KEY_REFRESH_TOKEN)

    fun userId(): String? = secureStore.getString(SecureStore.KEY_USER_ID)

    fun cachedEmail(): String? = secureStore.getString(SecureStore.KEY_USER_EMAIL)

    fun cachedDisplayName(): String? = secureStore.getString(SecureStore.KEY_USER_NAME)

    fun notifyExpired() {
        secureStore.clear()
        _events.tryEmit(SessionEvent.SessionExpired)
    }

    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val userId: String,
        val email: String? = null,
        val displayName: String? = null,
    )
}
