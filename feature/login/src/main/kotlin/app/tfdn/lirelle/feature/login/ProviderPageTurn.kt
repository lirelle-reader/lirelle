package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.network.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The provider's page a sign-in shows in a WebView of the app, while it is open, and what comes of it: the screen
 * of the page shows [page] and tells how it ended ([end]), once.
 *
 * Held in memory only, as the sign-in's view model is: a process stopped while the page is open loses the attempt,
 * and the page's screen, brought back without it, closes — the user begins again. Meant for the main thread.
 *
 * @param finish turns what the page left into a sign-in, in [scope]: the page is closed by then.
 * @param closed told when the page closed without signing in: with the error to show, or none when the user
 *   closed it.
 */
class ProviderPageTurn internal constructor(
    private val scope: CoroutineScope,
    private val finish: suspend (page: ProviderPage, cookies: String) -> Unit,
    private val closed: (error: ApiError?) -> Unit,
) {
    private val current = MutableStateFlow<ProviderPage?>(null)

    /** The page to show; null when none is open, or no more: its screen then closes. */
    val page: StateFlow<ProviderPage?> = current.asStateFlow()

    internal fun open(page: ProviderPage) {
        current.value = page
    }

    /** How the page ended. Told with no page open, or a second time, it is dropped. */
    fun end(end: ProviderPageEnd) {
        val page = current.value ?: return
        current.value = null
        when (end) {
            is ProviderPageEnd.SignedIn -> scope.launch { finish(page, end.cookies) }
            ProviderPageEnd.Refused -> closed(ApiError.SignInRefused)
            ProviderPageEnd.Unencrypted -> closed(ApiError.UnencryptedSignInPage)
            ProviderPageEnd.Closed -> closed(null)
        }
    }
}
