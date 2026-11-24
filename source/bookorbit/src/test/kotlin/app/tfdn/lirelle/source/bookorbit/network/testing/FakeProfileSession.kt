package app.tfdn.lirelle.source.bookorbit.network.testing

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.ProfileSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A profile's session held in memory, behaving like the real one: a write is in [session] when it returns,
 * once the profile is [gone] nothing is written any more, and an answer about tokens the profile no longer
 * holds changes nothing. The test sets [session] directly to sign in.
 *
 * For the tests of BookOrbit's renewal of a session and of its account, which came here from `core/network` with
 * the code they test.
 */
class FakeProfileSession(initial: Session) : ProfileSession {
    override val profileId = "p1"
    override val kind = ServerKind.BookOrbit
    override val session = MutableStateFlow(initial)

    /** The server refused to renew the session: set by [expire], and by a test, which also lowers it. */
    override val signInNeeded = MutableStateFlow(false)

    /** The profile was removed, or left for another. */
    @Volatile
    var gone = false

    override val open: Boolean get() = !gone

    /** The server refused to renew the session, and the profile was marked for it. */
    val expired: Boolean get() = signInNeeded.value

    override suspend fun updateCredential(credential: Credential, replacing: Credential) {
        if (!gone && holds(replacing)) session.update { it.copy(credential = credential) }
    }

    override suspend fun updateUser(user: UserProfile) {
        if (!gone) session.update { it.copy(user = user) }
    }

    override suspend fun expire(refused: Credential) {
        if (!gone && holds(refused)) signInNeeded.value = true
    }

    private fun holds(credential: Credential) = session.value.credential.isSameSignIn(credential)
}
