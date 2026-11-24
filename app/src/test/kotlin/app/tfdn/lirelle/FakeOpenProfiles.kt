package app.tfdn.lirelle

import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.profile.OpenProfiles
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileGraph
import app.tfdn.lirelle.core.session.profile.ShownSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** The open profile as a test says it is, told in the order the real one tells it. No object is behind it. */
class FakeOpenProfiles : OpenProfiles {
    override val current = MutableStateFlow<ProfileGraph?>(null)
    override val shown =
        MutableStateFlow(ShownSession(SessionState.SignedOut(serverUrl = null, reason = null), profileId = null))

    override var lastClosed: ProfileGraph? = null
        private set

    /** The profile is open before the screens are told. */
    fun signIn(profileId: String, session: Session) {
        current.value = ProfileGraph(profileId, object : ProfileComponent {}, CoroutineScope(SupervisorJob()))
        lastClosed = null
        shown.value = ShownSession(SessionState.SignedIn(session), profileId)
    }

    /** The update of an older installation could not finish at this start: nothing is open. */
    fun updateUnfinished() {
        shown.value = ShownSession(SessionState.Loading, profileId = null, updateUnfinished = true)
    }

    /** The screens are told of the change of profile before the profile left closes, as of a sign-out. */
    fun switching() {
        shown.value = ShownSession(SessionState.Loading, profileId = null, switching = true)
        lastClosed = current.value
        current.value = null
    }

    /** The screens are told before the profile closes. */
    fun signOut(state: SessionState.SignedOut) {
        shown.value = ShownSession(state, profileId = null)
        lastClosed = current.value
        current.value = null
    }
}
