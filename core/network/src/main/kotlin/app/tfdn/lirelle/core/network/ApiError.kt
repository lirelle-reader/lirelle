package app.tfdn.lirelle.core.network

import app.tfdn.lirelle.core.ids.BookId

/** Every failure the UI can show. One case per row of the spec's error table. */
sealed interface ApiError {
    data object ServerUnreachable : ApiError

    /** The address answers, but as no kind of server the app knows. */
    data object UnknownServer : ApiError
    data object InvalidCredentials : ApiError
    data class AccountLocked(val retryAfterSeconds: Long) : ApiError
    data object TooManyAttempts : ApiError
    data object DefaultPasswordMustChange : ApiError
    data object OidcCancelled : ApiError
    data object OidcRefused : ApiError

    /**
     * The account was recognised, but the server would not finish the sign-in: it refused to make the device's
     * credential (the account may not, or the device's name for it was taken). Nothing was saved.
     */
    data object SignInRefused : ApiError

    /**
     * The identity provider's flow led to a sign-in page in cleartext, which the app does not show: what is typed
     * there could be read, and the page changed, on its way.
     */
    data object UnencryptedSignInPage : ApiError

    /** The server does not know the key the user pasted to sign in. */
    data object KeyRefused : ApiError

    /**
     * The server runs a version the app cannot work with.
     *
     * @property version the version the server runs.
     * @property minimum the oldest version the app works with.
     */
    data class ServerTooOld(val version: String, val minimum: String) : ApiError

    /** No browser on the device can show the identity provider's page. */
    data object BrowserUnavailable : ApiError
    data object SessionExpired : ApiError

    /** The requested thing does not exist, or the user may not see it. */
    data object NotFound : ApiError

    /** The requested book was merged into another one, [target]. */
    data class Moved(val target: BookId) : ApiError

    /**
     * The server failed or is too busy to answer: nothing is wrong with the request, and the same
     * one can succeed later.
     */
    data object ServerFailed : ApiError

    /** The request was refused, or the failure does not say whether trying again can help. */
    data object Unexpected : ApiError
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(val error: ApiError) : ApiResult<Nothing> {
        /**
         * False when the request surely never reached the server: it could not connect (no network, an
         * unknown host, a refused connection, no route, a failed TLS handshake, a connection timeout). True
         * otherwise, as when no answer came after it was sent: the server may have acted on it. Not part of
         * the failure's equality: [error] is what the failure is.
         */
        var mayHaveReachedServer: Boolean = true
            private set

        companion object {
            /** A failure whose request surely never reached the server. */
            fun neverSent(error: ApiError): Failure = Failure(error).apply { mayHaveReachedServer = false }
        }
    }
}

/**
 * A failure already named as an [ApiError]: thrown inside a call for one that is not an HTTP or network error, or
 * by a source out of a page it failed to fetch, so that the paging needs to know no server's exceptions.
 */
class ApiErrorException(val error: ApiError) : Exception(error.toString())
