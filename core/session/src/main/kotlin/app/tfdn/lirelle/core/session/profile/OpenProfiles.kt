package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** A profile that is open: its objects and the scope they live in. */
class ProfileGraph(val profileId: String, val component: ProfileComponent, internal val scope: CoroutineScope)

/**
 * What the screens show: the session, and the profile it is the session of. One value, so that a screen never
 * sees the session of one profile with the id of another, as it could between two values followed apart.
 *
 * @property profileId the open profile while [state] is signed in; null otherwise.
 * @property switching the app is going from one profile to another: the one left is closing, or the other
 *   opening. [state] is loading meanwhile, never signed out — the sign-in screens are not for somebody who
 *   only changes profile — and the screens show a wait that says what is waited for.
 * @property updateUnfinished the update that makes the first profile of an older installation could not
 *   finish at this start: nothing is opened, [state] stays loading, and the screens say so rather than show
 *   a wait that never ends. The next start tries again.
 */
data class ShownSession(
    val state: SessionState,
    val profileId: String?,
    val updateUnfinished: Boolean = false,
    val switching: Boolean = false,
)

/**
 * Which profile is open, as the screens and [CurrentProfile] follow it. Apart from [ProfileGraphs], which
 * opens and closes them, so that what only follows can be given another in a test.
 */
interface OpenProfiles {
    /** The open profile; null while nobody is signed in. Its objects are for [CurrentProfile], not for a screen. */
    val current: StateFlow<ProfileGraph?>

    /**
     * The profile that was closed last, while no other has been built since; null otherwise, and before any
     * was closed in this process.
     *
     * Kept for the screens: they are told of a sign-out, or of a change of profile, before the profile closes,
     * but need a frame or more to leave, and a view model made meanwhile still asks for the profile's objects.
     * It is given these, which do nothing any more — their scope is cancelled — and is cleared with its
     * screen. Nothing else may use them: they are dropped when the next profile opens.
     */
    val lastClosed: ProfileGraph?

    /**
     * What the screens follow: signed in only once [current] is that profile's graph, signed out before it
     * closes — or switching, when another profile takes its place.
     */
    val shown: StateFlow<ShownSession>
}
