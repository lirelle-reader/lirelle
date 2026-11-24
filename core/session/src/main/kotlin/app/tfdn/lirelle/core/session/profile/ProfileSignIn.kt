package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.ServerKind
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * What a screen may know of the open profile's sign-in: whose profile it is, and whether its session waits
 * for a sign-in. For the screen that says so and offers to sign in again. A read only: a screen is not
 * handed the [ProfileSession] itself, which writes tokens.
 *
 * @property profileId the profile: what "sign in again" is asked for.
 * @property kind the kind of its server.
 * @property needed whether the server no longer takes the profile's session ([ProfileSession.signInNeeded]).
 * @param user the account's username as it is at the moment it is asked.
 */
class ProfileSignIn(
    val profileId: String,
    val kind: ServerKind,
    val needed: StateFlow<Boolean>,
    private val user: () -> String,
) {
    constructor(session: ProfileSession) : this(
        profileId = session.profileId,
        kind = session.kind,
        needed = session.signInNeeded,
        user = { session.session.value.user.username },
    )

    /** The account's username, as the profile is named wherever it is listed. Read when it is shown. */
    val username: String get() = user()
}

/**
 * The open profile's session, for the bridge below, which Hilt cannot inject it into. Internal: outside this
 * module the way to the profile's sign-in is [ProfileSignIn], which only reads.
 */
@EntryPoint
@InstallIn(ProfileComponent::class)
internal interface SessionProfileEntry {
    fun profileSession(): ProfileSession
}

/**
 * Gives view models the sign-in of the open profile, and nothing more of its session. Unscoped, and in no
 * component that outlives a profile: each view model gets that of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object ProfileSignInBridge {

    @Provides
    fun signIn(current: CurrentProfile): ProfileSignIn =
        ProfileSignIn(current.entry<SessionProfileEntry>().profileSession())
}
