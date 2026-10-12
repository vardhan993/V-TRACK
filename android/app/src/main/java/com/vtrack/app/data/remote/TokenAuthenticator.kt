package com.vtrack.app.data.remote

import com.vtrack.app.core.security.SessionStore
import com.vtrack.app.data.remote.dto.AuthResponse
import com.vtrack.app.data.remote.dto.RefreshRequest
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Handles 401s by exchanging the refresh token for a new access token.
 *
 * Correctness details that matter here:
 *  - Single-flight: several requests can fail at once, so the refresh is serialised and
 *    the *newest* stored token is reused instead of firing concurrent refreshes (the
 *    server rotates refresh tokens, so a concurrent second refresh would fail).
 *  - The refresh call itself uses a bare OkHttp client with no interceptor/authenticator,
 *    so it can never recurse.
 *  - If refresh fails, the session is cleared and the app is told to show sign-in rather
 *    than looping on 401s.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionStore: SessionStore,
    private val json: Json,
    @Named("bareClient") private val bareClient: OkHttpClient,
    @Named("apiBaseUrl") private val apiBaseUrl: String,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        if (path.startsWith("/v1/auth/")) return null
        if (responseCount(response) >= 2) return null

        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(lock) {
            // Another thread may have refreshed while this response was in flight.
            val stored = sessionStore.accessToken()
            if (!stored.isNullOrBlank() && stored != failedToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $stored")
                    .build()
            }

            val refreshToken = sessionStore.refreshToken()
            if (refreshToken.isNullOrBlank()) {
                sessionStore.clear()
                return null
            }

            val refreshed = exchangeRefreshToken(refreshToken)
            if (refreshed == null) {
                // Refresh token is no longer valid: force a clean sign-in.
                sessionStore.notifyExpired()
                return null
            }

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${refreshed.accessToken}")
                .build()
        }
    }

    private fun exchangeRefreshToken(refreshToken: String): AuthResponse? {
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/v1/auth/refresh")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        return try {
            bareClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string() ?: return null
                val auth = json.decodeFromString(AuthResponse.serializer(), text)
                sessionStore.save(
                    SessionStore.Tokens(
                        accessToken = auth.accessToken,
                        refreshToken = auth.refreshToken,
                        expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + auth.expiresIn,
                        userId = auth.user.id,
                        email = auth.user.email,
                        displayName = auth.user.displayName,
                    ),
                )
                auth
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
