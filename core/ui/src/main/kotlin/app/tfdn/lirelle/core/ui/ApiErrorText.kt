package app.tfdn.lirelle.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.core.network.ApiError

/** The sentence shown for [this] error on library screens. Raw server messages are never shown. */
@Composable
fun ApiError.libraryMessage(): String = when (this) {
    ApiError.ServerUnreachable -> stringResource(R.string.library_error_unreachable)
    ApiError.NotFound, is ApiError.Moved -> stringResource(R.string.library_error_not_found)
    ApiError.ServerFailed -> stringResource(R.string.library_error_server_failed)
    else -> stringResource(R.string.library_error_unexpected)
}

/** Retrying cannot bring back something that no longer exists. */
val ApiError.canRetry: Boolean get() = this != ApiError.NotFound && this !is ApiError.Moved

/** The device could not reach the server at all: the "No network" state, not an error of the server. */
val ApiError.isNoNetwork: Boolean get() = this == ApiError.ServerUnreachable
