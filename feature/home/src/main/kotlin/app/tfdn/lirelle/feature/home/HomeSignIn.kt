package app.tfdn.lirelle.feature.home

import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The profile Home is shown for, whose session the server no longer takes: what Home's notice names, and
 * what its button signs in again.
 *
 * @property profileId the profile to sign in again.
 * @property username its account's.
 * @property kind the kind of its server.
 */
data class ExpiredSession(val profileId: String, val username: String, val kind: ServerKind)

/** The profile as the notice names it while it waits for a sign-in; null while it does not. */
internal fun ProfileSignIn.expired(needed: Boolean = this.needed.value): ExpiredSession? =
    if (needed) ExpiredSession(profileId, username, kind) else null

/**
 * Follows, for Home, whether its profile waits for a sign-in.
 *
 * While it does, the profile asks its server nothing, and Home can only show what it already had: it says why
 * ([onChange]) instead of a failure. The moment the same account has signed in again nothing tells Home to
 * load — no pull, no screen coming back in front, no network returning — so this does ([onSignedIn]).
 *
 * @param onChange the profile as the notice names it, or null: at the start, and each time it changes.
 * @param onSignedIn the account signed in again: the server answers once more.
 */
internal class SignInFollower(
    private val signIn: ProfileSignIn,
    private val scope: CoroutineScope,
    private val onChange: (ExpiredSession?) -> Unit,
    private val onSignedIn: suspend () -> Unit,
) {
    fun start(): Job = scope.launch {
        var waited = signIn.needed.value
        signIn.needed.collect { needed ->
            onChange(signIn.expired(needed))
            if (waited && !needed) onSignedIn()
            waited = needed
        }
    }
}
