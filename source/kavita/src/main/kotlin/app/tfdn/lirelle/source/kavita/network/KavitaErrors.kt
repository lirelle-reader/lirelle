package app.tfdn.lirelle.source.kavita.network

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
internal enum class KavitaCall {
    /** Telling a Kavita server from another: an answer that is not Kavita's is no failure, a failing server is. */
    Probe,

    /** The session opened with a username and a password. */
    SignIn,

    /** The account read with the session a provider's sign-in opened in a WebView. */
    Session,

    /** The account read with a key the user pasted. */
    Key,

    /** The device's key made with the session of a sign-in. */
    Mint,

    /** A call made with the profile's key. */
    Authenticated,

    /** A public call with no meaning of its own. */
    Other,
}

/**
 * Turns anything a call to Kavita can throw into the [ApiError] the screens show. The status alone says what
 * happened: the body of a refusal or of a failure may be a stack trace, and is neither read into an error nor
 * told anywhere. Why a call could not reach the server is told to [log] (nowhere unless a debug build says so).
 */
internal class KavitaErrors(private val log: () -> NetworkLog = { NetworkDiagnostics.log }) {

    /** Whatever [block] throws becomes an answer; a cancellation goes on, and so does what is no exception. */
    suspend fun <T> call(kind: KavitaCall, block: suspend () -> T): ApiResult<T> {
        val attempt = runCatching { block() }
        val throwable = attempt.exceptionOrNull() ?: return ApiResult.Success(attempt.getOrThrow())
        if (throwable is CancellationException || throwable !is Exception) throw throwable
        val error = map(throwable, kind)
        val never = throwable.neverReachedServer()
        if (error == ApiError.ServerUnreachable) {
            log().warn("Unreachable (Kavita $kind): ${throwable.diagnosis()}, never sent: $never")
        }
        return if (never) ApiResult.Failure.neverSent(error) else ApiResult.Failure(error)
    }

    fun map(throwable: Throwable, kind: KavitaCall): ApiError = when (throwable) {
        is ApiErrorException -> throwable.error
        is HttpException -> status(throwable.code(), kind)
        is SerializationException -> ApiError.Unexpected
        is IOException -> ApiError.ServerUnreachable
        else -> ApiError.Unexpected
    }

    /** What the status [code] of an answer to a call of [kind] means. */
    fun status(code: Int, kind: KavitaCall): ApiError = when (code) {
        TooManyRequests -> ApiError.TooManyAttempts
        in ServerErrors -> ApiError.ServerFailed
        Unauthorized -> unauthorized(kind)
        in ClientErrors -> refused(code, kind)
        else -> ApiError.Unexpected
    }

    /** What a credential the server does not take means: each call carries its own. */
    private fun unauthorized(kind: KavitaCall): ApiError = when (kind) {
        KavitaCall.SignIn -> ApiError.InvalidCredentials
        KavitaCall.Key -> ApiError.KeyRefused
        // The provider's sign-in left no session the server knows; or the session would not make the key.
        KavitaCall.Session, KavitaCall.Mint -> ApiError.SignInRefused
        KavitaCall.Authenticated -> ApiError.SessionExpired
        KavitaCall.Probe, KavitaCall.Other -> ApiError.Unexpected
    }

    /** What any other refusal means. */
    private fun refused(code: Int, kind: KavitaCall): ApiError = when {
        // A name the account already gave a key, twice over, or an account that may not make keys; a session
        // the server would not let read the account.
        kind == KavitaCall.Mint || kind == KavitaCall.Session -> ApiError.SignInRefused
        kind == KavitaCall.Authenticated && code == NotFound -> ApiError.NotFound
        else -> ApiError.Unexpected
    }

    private companion object {
        const val Unauthorized = 401
        const val NotFound = 404
        const val TooManyRequests = 429
        val ClientErrors = 400..499
        val ServerErrors = 500..599
    }
}
