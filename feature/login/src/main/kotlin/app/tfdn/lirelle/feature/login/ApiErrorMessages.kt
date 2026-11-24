package app.tfdn.lirelle.feature.login

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.SignInIdentifier

private const val SecondsPerMinute = 60

/** Whole minutes to wait, rounded up, never less than one. */
internal fun lockedMinutes(retryAfterSeconds: Long): Int =
    ((retryAfterSeconds + SecondsPerMinute - 1) / SecondsPerMinute).coerceAtLeast(1).toInt()

/** Wrong credentials, naming what the server takes with the password. */
@get:StringRes
private val SignInIdentifier.invalidCredentials: Int
    get() = when (this) {
        SignInIdentifier.Username -> R.string.error_invalid_credentials
        SignInIdentifier.Email -> R.string.error_invalid_email_credentials
    }

/**
 * The localised sentence shown for [this] error. Raw server messages are never shown.
 *
 * @param identifier what the server takes with the password, which wrong credentials name.
 */
@Composable
fun ApiError.message(identifier: SignInIdentifier = SignInIdentifier.Username): String = when (this) {
    ApiError.InvalidCredentials -> stringResource(identifier.invalidCredentials)
    is ApiError.AccountLocked -> {
        val minutes = lockedMinutes(retryAfterSeconds)
        pluralStringResource(R.plurals.error_account_locked, minutes, minutes)
    }
    else -> stringResource(plainMessage)
}

/** The sentence of an error that says nothing more than what it is; [message] words the others itself. */
@get:StringRes
private val ApiError.plainMessage: Int
    get() = when (this) {
        ApiError.ServerUnreachable -> R.string.error_server_unreachable
        ApiError.UnknownServer -> R.string.error_unknown_server
        ApiError.TooManyAttempts -> R.string.error_too_many_attempts
        ApiError.DefaultPasswordMustChange -> R.string.error_default_password
        ApiError.OidcCancelled -> R.string.error_oidc_cancelled
        ApiError.OidcRefused -> R.string.error_oidc_refused
        ApiError.SignInRefused -> R.string.error_sign_in_refused
        ApiError.UnencryptedSignInPage -> R.string.error_unencrypted_sign_in_page
        ApiError.BrowserUnavailable -> R.string.error_browser_unavailable
        ApiError.SessionExpired -> R.string.error_session_expired
        ApiError.ServerFailed -> R.string.error_server_failed
        // Wrong credentials and a locked account are worded by [message], with what they carry.
        ApiError.NotFound, is ApiError.Moved, ApiError.Unexpected, ApiError.InvalidCredentials,
        is ApiError.AccountLocked -> R.string.error_unexpected
    }
