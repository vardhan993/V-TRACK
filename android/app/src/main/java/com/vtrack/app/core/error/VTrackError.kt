package com.vtrack.app.core.error

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.HttpException

/**
 * Every failure that can reach the UI is expressed as one of these types so screens
 * can show an actionable message instead of a raw stack trace.
 */
sealed class VTrackError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No usable connectivity. Work is queued locally and retried by WorkManager. */
    class Offline(cause: Throwable? = null) :
        VTrackError("You're offline. Your activity is saved and will sync automatically.", cause)

    /** Access token missing/expired and refresh failed — the user must sign in again. */
    class Unauthorized : VTrackError("Your session expired. Please sign in again.")

    /** Rate limited or blocked by the server's anti-abuse rules. */
    class RateLimited(retryAfterSeconds: Long?) : VTrackError(
        if (retryAfterSeconds != null) {
            "Too many requests. Try again in ${retryAfterSeconds}s."
        } else {
            "Too many requests. Please slow down and try again shortly."
        },
    )

    /** Server understood the request but refused it (validation, rules, ownership). */
    class Rejected(val code: String, message: String) : VTrackError(message)

    /** Server-side failure (5xx) or an unexpected status. */
    class Server(val status: Int, message: String) : VTrackError(message)

    class Unknown(cause: Throwable?) : VTrackError("Something went wrong. Please try again.", cause)

    companion object {
        fun from(throwable: Throwable): VTrackError = when (throwable) {
            is VTrackError -> throwable
            is UnknownHostException -> Offline(throwable)
            is SocketTimeoutException -> Offline(throwable)
            is IOException -> Offline(throwable)
            is HttpException -> {
                val code = throwable.code()
                when (code) {
                    401 -> Unauthorized()
                    403 -> Rejected("forbidden", "You don't have access to that.")
                    409 -> Rejected("conflict", "That conflicts with an existing record.")
                    429 -> RateLimited(
                        throwable.response()?.headers()?.get("Retry-After")?.toLongOrNull(),
                    )
                    in 400..499 -> Rejected("invalid_request", "The request was rejected. Check your input.")
                    else -> Server(code, "V-Track servers are having trouble. Try again shortly.")
                }
            }
            else -> Unknown(throwable)
        }
    }
}

/** Maps a failure into a [VTrackError] while preserving the successful value. */
inline fun <T> Result<T>.mapToVTrackError(): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { Result.failure(VTrackError.from(it)) },
)

/** User-facing message for any throwable. */
fun Throwable.userMessage(): String = when (this) {
    is VTrackError -> message ?: "Something went wrong."
    else -> "Something went wrong. Please try again."
}
