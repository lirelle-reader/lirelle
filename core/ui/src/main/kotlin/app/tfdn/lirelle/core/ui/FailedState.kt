package app.tfdn.lirelle.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.tfdn.lirelle.core.network.ApiError
import kotlinx.coroutines.flow.StateFlow

/**
 * What leads to the downloaded books from a screen that could not be loaded for lack of network: selecting the
 * Downloads destination. The shell provides it; null outside it, where a failed load is an [ErrorState].
 */
val LocalOpenDownloads = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Whether the device has a network, as the shell knows it: a [NoNetworkState] tries again when it comes back.
 * Null outside the shell, where nothing is tried again without a tap.
 */
val LocalDeviceOnline = staticCompositionLocalOf<StateFlow<Boolean>?> { null }

/**
 * Takes the place of a whole screen or list that could not be loaded: the [NoNetworkState] for lack of
 * network, where the downloaded books can be reached ([LocalOpenDownloads]) or the profile waits for a sign-in
 * ([LocalSignInNeeded]), which it then says; the [ErrorState] otherwise.
 *
 * @param scrolls the state for lack of network, taller than an error, scrolls by itself. False where what
 *   holds it scrolls already: in a list, or under a modifier that scrolls.
 */
@Composable
fun FailedState(error: ApiError, onRetry: () -> Unit, modifier: Modifier = Modifier, scrolls: Boolean = false) {
    val onOpenDownloads = LocalOpenDownloads.current
    val waitsForSignIn = signInWait() != null
    if (error.isNoNetwork && (onOpenDownloads != null || waitsForSignIn)) {
        NoNetworkState(onRetry = onRetry, onOpenDownloads = onOpenDownloads, modifier = modifier, scrolls = scrolls)
    } else {
        ErrorState(error = error, onRetry = onRetry, modifier = modifier)
    }
}
