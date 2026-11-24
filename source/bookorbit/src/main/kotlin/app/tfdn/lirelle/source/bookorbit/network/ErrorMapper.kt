package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.NetworkDiagnostics
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.network.diagnosis
import app.tfdn.lirelle.core.network.neverReachedServer
import app.tfdn.lirelle.source.bookorbit.network.dto.ApiErrorBody
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import retrofit2.HttpException

/** Which kind of call failed. The same HTTP status means different things per kind. */
internal enum class CallKind {
    /** Checking that an address is a BookOrbit server. */
    Probe,

    /** Password sign-in. */
    Login,

    /** Exchanging the OIDC code. */
    OidcCallback,

    /** Any call made with the session's access token. */
    Authenticated,

    /** Public calls with no special meaning. */
    Other,
}

/**
 * Turns anything a call can throw into the [ApiError] the UI shows. Why a call could not reach the server
 * is told to [log] (nowhere unless a debug build says so): its kind, the failure and its causes, and
 * whether the request never left.
 */
internal class ErrorMapper(private val json: Json, private val log: () -> NetworkLog = { NetworkDiagnostics.log }) {

    fun map(throwable: Throwable, kind: CallKind): ApiError = when (throwable) {
        is ApiErrorException -> throwable.error
        is HttpException -> mapHttp(throwable, kind)
        // A non-JSON answer to the probe is some other web server.
        is SerializationException -> if (kind == CallKind.Probe) ApiError.UnknownServer else ApiError.Unexpected
        is IOException -> ApiError.ServerUnreachable
        else -> ApiError.Unexpected
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> call(kind: CallKind, block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Exception) {
        val error = map(throwable, kind)
        val never = neverSent(throwable)
        if (error == ApiError.ServerUnreachable) {
            log().warn("Unreachable ($kind): ${throwable.diagnosis()}, never sent: $never")
        }
        if (never) ApiResult.Failure.neverSent(error) else ApiResult.Failure(error)
    }

    /**
     * Whether [throwable] says the request never left: the connection itself could not be made. A timeout
     * or a reset once connected, or a failure while reading the answer, may come after the server acted.
     */
    fun neverSent(throwable: Throwable): Boolean = throwable.neverReachedServer()

    private fun mapHttp(exception: HttpException, kind: CallKind): ApiError {
        val code = exception.code()
        if (code == TooManyRequests) return ApiError.TooManyAttempts
        if (code in ServerErrors) return ApiError.ServerFailed
        return when (kind) {
            CallKind.Probe -> if (code in ClientErrors) ApiError.UnknownServer else ApiError.Unexpected
            CallKind.Login -> mapLogin(code, errorBody(exception))
            CallKind.OidcCallback -> if (code in ClientErrors) ApiError.OidcRefused else ApiError.Unexpected
            CallKind.Authenticated -> mapAuthenticated(code, errorBody(exception))
            CallKind.Other -> ApiError.Unexpected
        }
    }

    private fun mapLogin(code: Int, body: ApiErrorBody?): ApiError = when {
        body?.errorCode == AccountLockedCode -> ApiError.AccountLocked(body.retryAfterSeconds ?: 0)
        code == Unauthorized -> ApiError.InvalidCredentials
        else -> ApiError.Unexpected
    }

    private fun mapAuthenticated(code: Int, body: ApiErrorBody?): ApiError = when {
        code == Unauthorized -> ApiError.SessionExpired
        code == Forbidden && body.hasMessage(PasswordChangeRequired) -> ApiError.DefaultPasswordMustChange
        code == NotFound -> mergedTarget(body)?.let(ApiError::Moved) ?: ApiError.NotFound
        // How the server says "busy, try again": `DELIVERY_BUSY` and the like.
        code == Conflict -> ApiError.ServerFailed
        else -> ApiError.Unexpected
    }

    private fun mergedTarget(body: ApiErrorBody?): BookId? =
        body?.takeIf { it.errorCode == BookMergedCode }
            ?.errorMeta?.get("targetBookId")
            ?.let { (it as? JsonPrimitive)?.longOrNull }
            ?.let(::bookIdOf)

    private fun errorBody(exception: HttpException): ApiErrorBody? = try {
        exception.response()?.errorBody()?.string()?.let { json.decodeFromString<ApiErrorBody>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun ApiErrorBody?.hasMessage(text: String): Boolean =
        (this?.message as? JsonPrimitive)?.takeIf { it.isString }?.content == text

    private companion object {
        const val Unauthorized = 401
        const val Forbidden = 403
        const val NotFound = 404
        const val Conflict = 409
        const val TooManyRequests = 429
        val ClientErrors = 400..499
        val ServerErrors = 500..599
        const val AccountLockedCode = "account_locked"
        const val BookMergedCode = "BOOK_MERGED"

        /** Sent by the server's guard while a password account still has its default password. */
        const val PasswordChangeRequired = "Password change required"
    }
}
