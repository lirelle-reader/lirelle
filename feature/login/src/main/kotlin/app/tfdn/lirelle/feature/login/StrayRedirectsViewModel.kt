package app.tfdn.lirelle.feature.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The OIDC redirects that reach the signed-in app while it shows no sign-in screen, and the sign-in those
 * screens leave pending when they go.
 *
 * While the sign-in screens are shown over a profile, their own view model takes the redirects. Once they are
 * left — back, or anything else that takes them away — the sign-in they may have started waits for nothing any
 * more: it is cancelled, so that a redirect coming later finds nothing to complete and signs nobody in.
 *
 * A redirect that answers a sign-in still pending while no sign-in screen is shown can only answer one that a
 * process ended during — the browser was in front — and whose screen was not shown again at the restart: it is
 * completed, as the sign-in screen does when nobody is signed in, and its account's profile becomes current.
 *
 * About no profile: it holds the device's sign-in and nothing of the open profile.
 */
@HiltViewModel
class StrayRedirectsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val redirects: OidcRedirectRelay,
) : ViewModel() {

    /** Whether the sign-in screens were shown when this was last told: they are left when they no longer are. */
    private var shown = false

    /**
     * Follows the redirects for as long as the sign-in screens stay shown or not, as [signInShown] says: called
     * again, the call before cancelled, each time that changes. Returns at once while they are shown.
     */
    suspend fun follow(signInShown: Boolean) {
        if (signInShown) {
            shown = true
            return
        }
        if (shown) {
            auth.cancelOidc()
            shown = false
        }
        redirects.redirects.collect { redirectUri ->
            // Not the answer of a sign-in that waits: that sign-in was cancelled, or the link was not asked for
            // — any app can send it. Dropped, and a sign-in that waits for another answer goes on waiting.
            if (auth.answersPendingOidc(redirectUri)) auth.completeOidc(redirectUri)
        }
    }
}

/**
 * Placed in the signed-in app, beside its screens: sees to the OIDC redirects that come while the sign-in
 * screens are not shown over the profile, and to the sign-in they leave pending ([StrayRedirectsViewModel]).
 *
 * @param signInShown whether the sign-in screens are shown over the profile now.
 */
@Composable
fun RedirectsOutsideSignIn(signInShown: Boolean, viewModel: StrayRedirectsViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel, signInShown) { viewModel.follow(signInShown) }
}
