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
        else -> ApiError.Unexpected
    }

    private companion object {
        const val Unauthorized = 401
        const val TooManyRequests = 429
        val ServerErrors = 500..599
    }
}
