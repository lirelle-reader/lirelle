package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.NetworkDiagnostics
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.network.diagnosis
import app.tfdn.lirelle.core.network.neverReachedServer
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Which kind of call failed: the same status means different things for each. */
internal enum class AbsCall {
    /** Telling an Audiobookshelf server from another: an answer that is not its is no failure, a failing server is. */
    Probe,

    /** The session opened with a name and a password. */
    SignIn,

    /** The session opened by exchanging the code the server's identity provider gave, through the browser. */
    OidcExchange,

    /** A call made with the profile's access token. */
    Authenticated,

    /** A public call with no meaning of its own: the server's ways of signing in. */
    Other,
}

/**
 * Turns anything a call to Audiobookshelf can throw into the [ApiError] the screens show. The status alone says what
 * happened: the body of a refusal or of a failure is a short text, and is neither read into an error nor told
 * anywhere. Why a call could not reach the server is told to [log] (nowhere unless a debug build says so).
 */
internal class AbsErrors(private val log: () -> NetworkLog = { NetworkDiagnostics.log }) {

    /** Whatever [block] throws becomes an answer; a cancellation goes on, and so does what is no exception. */
    suspend fun <T> call(kind: AbsCall, block: suspend () -> T): ApiResult<T> {
        val attempt = runCatching { block() }
        val throwable = attempt.exceptionOrNull() ?: return ApiResult.Success(attempt.getOrThrow())
        if (throwable is CancellationException || throwable !is Exception) throw throwable
        val error = map(throwable, kind)
        val never = throwable.neverReachedServer()
        if (error == ApiError.ServerUnreachable) {
            log().warn("Unreachable (Audiobookshelf $kind): ${throwable.diagnosis()}, never sent: $never")
        }
        return if (never) ApiResult.Failure.neverSent(error) else ApiResult.Failure(error)
    }

    fun map(throwable: Throwable, kind: AbsCall): ApiError = when (throwable) {
        is ApiErrorException -> throwable.error
        is HttpException -> status(throwable.code(), kind)
        is SerializationException -> ApiError.Unexpected
        is IOException -> ApiError.ServerUnreachable
        else -> ApiError.Unexpected
    }

    /**
     * What the status [code] of an answer to a call of [kind] means. Past its login limit — sign-ins and renewals
     * alike, 40 per 10 minutes per address — the server answers 429.
     */
    fun status(code: Int, kind: AbsCall): ApiError = when {
        code == TooManyRequests -> ApiError.TooManyAttempts
        code in ServerErrors -> ApiError.ServerFailed
        // A wrong name or password, an unknown or disabled account: one answer, which says no more.
        code == Unauthorized && kind == AbsCall.SignIn -> ApiError.InvalidCredentials
        kind == AbsCall.Authenticated -> authenticatedStatus(code)
        kind == AbsCall.OidcExchange -> exchangeStatus(code)
        else -> ApiError.Unexpected
    }

    /**
     * What a refused exchange of the provider's code means. The server answers a failed one — a code refused or used,
     * an account unknown, disabled or not let in — with a redirect to its web sign-in page; a refusal as such
     * likewise. Without the sign-in's session (the server restarted, or the session was used by an exchange already)
     * it answers 400: the sign-in can no longer end, as one begun too long ago, and the user begins again.
     */
    private fun exchangeStatus(code: Int): ApiError = when (code) {
        in Redirects, Unauthorized, Forbidden -> ApiError.SignInRefused
        BadRequest -> ApiError.OidcRefused
        else -> ApiError.Unexpected
    }

    /**
     * What a refusal of a call made with the profile's access token means: one the server no longer takes ended the
     * session (the profile's authorizer marks it when it cannot be renewed); something it does not have, or that the
     * account may not see (an item or a library outside its rights), is not found.
     */
    private fun authenticatedStatus(code: Int): ApiError = when (code) {
        Unauthorized -> ApiError.SessionExpired
        Forbidden, NotFound -> ApiError.NotFound
        else -> ApiError.Unexpected
    }

    private companion object {
        const val BadRequest = 400
        const val Unauthorized = 401
        const val Forbidden = 403
        const val NotFound = 404
        const val TooManyRequests = 429
        val Redirects = 300..399
        val ServerErrors = 500..599
    }
}
