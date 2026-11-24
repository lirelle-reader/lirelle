package app.tfdn.lirelle.core.downloads.store

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import kotlinx.serialization.Serializable

private const val Queued = "Queued"
private const val Downloading = "Downloading"
private const val Waiting = "Waiting"
private const val Failed = "Failed"
private const val Complete = "Complete"

private const val NoSpace = "NoSpace"
private const val Server = "Server"
private const val NotAllowed = "NotAllowed"
private const val NotAnEpub = "NotAnEpub"
private const val Damaged = "Damaged"

/**
 * A [FormatState] under a stable name. [receivedBytes] is the size on the device of a complete one;
 * [wait] is the name of a [WaitReason].
 */
@Serializable
internal data class StoredState(
    val name: String,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
    val wait: String? = null,
    val failure: StoredFailure? = null,
)

/**
 * A [DownloadFailure] under a stable name; [error] and its [payload] or [target] are those of a server failure:
 * a number for a lock's wait, the book a merged one moved to.
 */
@Serializable
internal data class StoredFailure(
    val name: String,
    val missingBytes: Long = 0,
    val error: String? = null,
    val payload: Long? = null,
    val target: BookId? = null,
)

internal fun FormatState.stored(): StoredState = when (this) {
    FormatState.Queued -> StoredState(Queued)
    is FormatState.Downloading -> StoredState(Downloading, receivedBytes, totalBytes)
    is FormatState.Waiting -> StoredState(Waiting, receivedBytes, totalBytes, wait = reason.name)
    is FormatState.Failed -> StoredState(Failed, receivedBytes, failure = reason.stored())
    is FormatState.Complete -> StoredState(Complete, bytes)
}

/**
 * A state this version does not know is queued again: the queue decides what is left to fetch. A
 * wait it does not know is for a network, a failure it does not know is the server's.
 */
internal fun StoredState.state(): FormatState = when (name) {
    Downloading -> FormatState.Downloading(receivedBytes, totalBytes)
    Waiting -> FormatState.Waiting(
        reason = WaitReason.entries.firstOrNull { it.name == wait } ?: WaitReason.NoNetwork,
        receivedBytes = receivedBytes,
        totalBytes = totalBytes,
    )
    Failed -> FormatState.Failed(failure.failure(), receivedBytes)
    Complete -> FormatState.Complete(receivedBytes)
    else -> FormatState.Queued
}

private fun DownloadFailure.stored(): StoredFailure = when (this) {
    is DownloadFailure.NoSpace -> StoredFailure(NoSpace, missingBytes)
    is DownloadFailure.Server ->
        StoredFailure(Server, error = error.storedName(), payload = error.payload(), target = error.target())
    DownloadFailure.NotAllowed -> StoredFailure(NotAllowed)
    DownloadFailure.NotAnEpub -> StoredFailure(NotAnEpub)
    DownloadFailure.Damaged -> StoredFailure(Damaged)
}

private fun StoredFailure?.failure(): DownloadFailure = when (this?.name) {
    NoSpace -> DownloadFailure.NoSpace(missingBytes)
    NotAllowed -> DownloadFailure.NotAllowed
    NotAnEpub -> DownloadFailure.NotAnEpub
    Damaged -> DownloadFailure.Damaged
    else -> DownloadFailure.Server(apiError(this?.error, this?.payload, this?.target))
}

private const val AccountLocked = "AccountLocked"
private const val Moved = "Moved"
private const val Unexpected = "Unexpected"

/** The name each error without a payload is stored under: it stays when the class is renamed. */
private val PlainNames: Map<ApiError, String> = mapOf(
    ApiError.ServerUnreachable to "ServerUnreachable",
    // Its name when the app knew BookOrbit alone: what was stored under it still reads.
    ApiError.UnknownServer to "NotBookOrbit",
    ApiError.InvalidCredentials to "InvalidCredentials",
    ApiError.TooManyAttempts to "TooManyAttempts",
    ApiError.DefaultPasswordMustChange to "DefaultPasswordMustChange",
    ApiError.OidcCancelled to "OidcCancelled",
    ApiError.OidcRefused to "OidcRefused",
    ApiError.SignInRefused to "SignInRefused",
    ApiError.UnencryptedSignInPage to "UnencryptedSignInPage",
    ApiError.BrowserUnavailable to "BrowserUnavailable",
    ApiError.SessionExpired to "SessionExpired",
    ApiError.NotFound to "NotFound",
    ApiError.ServerFailed to "ServerFailed",
    ApiError.Unexpected to Unexpected,
)

private val PlainErrors: Map<String, ApiError> = PlainNames.entries.associate { (error, name) -> name to error }

/** An error added since, which has no name here yet, is stored as [ApiError.Unexpected]. */
private fun ApiError.storedName(): String = when (this) {
    is ApiError.AccountLocked -> AccountLocked
    is ApiError.Moved -> Moved
    else -> PlainNames[this] ?: Unexpected
}

private fun ApiError.payload(): Long? = (this as? ApiError.AccountLocked)?.retryAfterSeconds

private fun ApiError.target(): BookId? = (this as? ApiError.Moved)?.target

/** A name this version does not know, or an error without the payload it needs, is [ApiError.Unexpected]. */
private fun apiError(name: String?, payload: Long?, target: BookId?): ApiError = when (name) {
    AccountLocked -> payload?.let(ApiError::AccountLocked)
    Moved -> target?.let(ApiError::Moved)
    else -> PlainErrors[name]
} ?: ApiError.Unexpected
