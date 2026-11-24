package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session of one profile, for that profile's own objects. Unlike [SessionStore] it never means "whoever
 * is current": a call that ends after its profile closed writes to its own profile, or nowhere.
 */
interface ProfileSession {
    val profileId: String

    val kind: ServerKind

    /** The session as last known; keeps its last value once the profile is gone. */
    val session: StateFlow<Session>

    /**
     * Whether the server no longer takes this profile's session ([expire]), until the same account signs in
     * again. The profile stays as it is, open and with everything it holds; it asks the server nothing
     * meanwhile, as if the device had no network. When it falls, [session] already carries the new tokens.
     */
    val signInNeeded: StateFlow<Boolean>

    /**
     * Whether the profile is still there to act for: false once it was removed or left for another, or
     * its objects were thrown away, and never true again. A session that expired does not close it. [session]
     * still gives the last tokens known then: a request may go out with them, but nothing new is started in
     * the profile's name, least of all a renewal, whose refresh token the sign-out is handing back to the
     * server at that very moment.
     */
    val open: Boolean

    /**
     * Writes [credential] to this profile in the place of [replacing], the one it was renewed from; nothing
     * when the profile no longer holds that sign-in, its account having signed in again meanwhile. Either way
     * [session] is what the profile holds when this returns: whoever reads it next, to renew in its turn, must
     * not find the credential that was just replaced.
     */
    suspend fun updateCredential(credential: Credential, replacing: Credential)

    /** Likewise for the account. */
    suspend fun updateUser(user: UserProfile)

    /**
     * The server refused [refused]: the profile is kept, marked as needing sign-in — unless it no longer holds
     * that sign-in, its account having signed in again while the answer was on its way: that refusal says
     * nothing of the new session. When this returns the mark is in [signInNeeded], or [session] carries the
     * credential that replaced [refused]: whoever asks next must not send the dead one again.
     */
    suspend fun expire(refused: Credential)
}

/**
 * The session of [profile] as [store] keeps it, followed for as long as [life] lives. Every write names the
 * profile, so the store drops it by itself once the profile is gone.
 *
 * [session] and [signInNeeded] are told of a write by the write itself, before it returns, and of what others
 * wrote — the same account signing in again, which replaces the tokens and clears the mark while the
 * profile stays open — by following the store. Neither publishes what it was
 * handed: both read the store again, one at a time, so that two of them ending out of order cannot leave the
 * older session in place.
 */
class StoreProfileSession(
    profile: Profile,
    private val store: ProfileStore,
    private val life: CoroutineScope,
) : ProfileSession {

    override val profileId: String = profile.id
    override val kind: ServerKind = profile.kind

    private val mutableSession = MutableStateFlow(profile.session)
    override val session: StateFlow<Session> = mutableSession.asStateFlow()

    private val mutableSignInNeeded = MutableStateFlow(profile.signInNeeded)
    override val signInNeeded: StateFlow<Boolean> = mutableSignInNeeded.asStateFlow()

    private val publishing = Mutex()

    /**
     * Kept once seen: a profile that was removed or left never opens again as this object. The same account
     * signing in again after a removal is another profile, with a session of its own; signing in again to a
     * profile that is still there — one that needed to — keeps it, and this object with it.
     */
    @Volatile
    private var gone = false

    /**
     * Read from the store at the moment it is asked, not from what was followed: a sign-out is known here as
     * soon as the store has it, before whoever follows the store has closed anything. A profile is open only
     * while it is the current one, and while [life] lasts.
     */
    override val open: Boolean
        get() {
            if (!gone && (store.current.value?.id != profileId || !life.isActive)) gone = true
            return !gone
        }

    init {
        life.launch { store.current.collect { current -> if (current?.id == profileId) publish() } }
    }

    override suspend fun updateCredential(credential: Credential, replacing: Credential) {
        store.updateCredential(profileId, credential, replacing)
        // Also when nothing was written: a sign-in that got there first is then known here, not later.
        publish()
    }

    override suspend fun updateUser(user: UserProfile) {
        if (store.updateUser(profileId, user) != null) publish()
    }

    /**
     * The profile as the store has it now, which is never older than what was published before. The mark
     * rises before the session is told and falls after it: nobody finds the mark down beside the tokens the
     * server refused, and renews with them.
     */
    private suspend fun publish() = publishing.withLock {
        val stored = store.profile(profileId) ?: return@withLock
        if (stored.signInNeeded) mutableSignInNeeded.value = true
        mutableSession.value = stored.session
        if (!stored.signInNeeded) mutableSignInNeeded.value = false
    }

    override suspend fun expire(refused: Credential) {
        store.needSignIn(profileId, refused)
        // Also when nobody was marked: the tokens that replaced the refused ones are then known here, not later.
        publish()
    }
}
