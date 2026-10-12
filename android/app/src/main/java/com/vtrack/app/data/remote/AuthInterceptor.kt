package com.vtrack.app.data.remote

import com.vtrack.app.core.security.SecureStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the short-lived access token to every request except the auth endpoints
 * themselves (which must work while signed out, and where a stale token would cause
 * confusing 401s).
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val secureStore: SecureStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val isAuthEndpoint = path.startsWith("/v1/auth/")

        val token = if (isAuthEndpoint) null else secureStore.getString(SecureStore.KEY_ACCESS_TOKEN)

        val authorized = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .header("X-Client", "android")
                .build()
        }
        return chain.proceed(authorized)
    }
}
