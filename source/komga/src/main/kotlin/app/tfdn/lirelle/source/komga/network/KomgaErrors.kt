package app.tfdn.lirelle.source.komga.network

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
internal enum class KomgaCall {
    /** Telling a Komga server from another: an answer that is not Komga's is no failure, a failing server is. */
    Probe,

    /** The account read with its email and password. */
    SignIn,

    /** The account read with the session a provider's sign-in opened in a WebView. */
    Session,

    /** The device's key made with the session of a sign-in. */
    Mint,

    /** A call made with the profile's key. */
    Authenticated,

    /**
     * A call made with the profile's key for the library's or the history's items. Komga refuses (403) an item the
     * account may not see (a library not shared with it, a content restriction): for the app, it is not there.
     */
    Content,

    /** A public call with no meaning of its own. */
    Other,
}

/**
 * Turns anything a call to Komga can throw into the [ApiError] the screens show. Komga's refusals say little in
 * their body (`{status, error, message}`, the message a code): the status says what happened. Why a call could
 * not reach the server is told to [log] (nowhere unless a debug build says so).
 */
internal class KomgaErrors(private val log: () -> NetworkLog = { NetworkDiagnostics.log }) {

    @Suppress("TooGenericExceptionCaught") // Whatever a call throws becomes an answer; a cancellation goes on.
    suspend fun <T> call(kind: KomgaCall, block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Exception) {
        val error = map(throwable, kind)
        val never = throwable.neverReachedServer()
        if (error == ApiError.ServerUnreachable) {
            log().warn("Unreachable (Komga $kind): ${throwable.diagnosis()}, never sent: $never")
        }
        if (never) ApiResult.Failure.neverSent(error) else ApiResult.Failure(error)
    }

    fun map(throwable: Throwable, kind: KomgaCall): ApiError = when (throwable) {
        is ApiErrorException -> throwable.error
        is HttpException -> status(throwable.code(), kind)
        is SerializationException -> ApiError.Unexpected
        is IOException -> ApiError.ServerUnreachable
        else -> ApiError.Unexpected
    }

    /** What the status [code] of an answer to a call of [kind] means. */
    fun status(code: Int, kind: KomgaCall): ApiError = when {
        code == TooManyRequests -> ApiError.TooManyAttempts
        code in ServerErrors -> ApiError.ServerFailed
        kind == KomgaCall.SignIn && code == Unauthorized -> ApiError.InvalidCredentials
        // A comment the account already gave a key (`ERR_1034`), or an account that may not make keys.
        kind == KomgaCall.Mint && code in ClientErrors -> ApiError.SignInRefused
        // The provider's sign-in left no session the server knows, or one it would not let read the account.
        kind == KomgaCall.Session && code in ClientErrors -> ApiError.SignInRefused
        kind in Keyed && code == Unauthorized -> ApiError.SessionExpired
        kind in Keyed && isAbsent(code, kind) -> ApiError.NotFound
        else -> ApiError.Unexpected
    }

    /** An item gone, or (for [KomgaCall.Content]) one the account may not see. */
    private fun isAbsent(code: Int, kind: KomgaCall): Boolean =
        code == NotFound || kind == KomgaCall.Content && code == Forbidden

    private companion object {
        val Keyed = setOf(KomgaCall.Authenticated, KomgaCall.Content)
        const val Unauthorized = 401
        const val Forbidden = 403
        const val NotFound = 404
        const val TooManyRequests = 429
        val ClientErrors = 400..499
        val ServerErrors = 500..599
    }
}
