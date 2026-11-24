package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * The profiles of this device, kept in DataStore, and the session of the current one: for the rest of the
 * code, signing in is saving a session and signing out is clearing the profile that was read as current.
 *
 * Credentials are encrypted; the rest is stored as it is. A profile that was removed stays listed, as being
 * removed, until its folders are gone ([forget]); it is never opened again, and its id is never given to
 * another: signing in to the same account afterwards makes a new profile.
 *
 * Several profiles are kept, in order of use: signing in to an account that has none adds one beside the
 * others, and the profile that becomes current — signed in, switched to — is from then on the first. Removing
 * the current one shows the one used last before it; nobody is signed out while a profile is left.
 *
 * A profile whose tokens the keystore could not decrypt at a start, without saying they are lost, is neither
 * opened nor listed at that start — the app shows the profile used last before it, or signed out when there is
 * none — and is left exactly as it is written: it opens at the next start that can read it, and signing in to
 * its account meanwhile takes it over, with what it kept.
 *
 * A profile being moved in — the one the migration makes of an installation from before the profiles — is
 * listed too, and neither opened nor current until the migration has given it all its files.
 *
 * Each profile says the format of what its folders hold ([Profile.dataFormat]); one written before the format
 * was kept is of the old one, and the account ids it stored as numbers read as their text. Whoever opens a
 * profile of an older format empties its folders first and says so ([ProfileDataFormats]).
 *
 * An entry of the file that this version cannot understand — a kind of server it does not know, a field
 * missing — is kept as it was written and its id stays listed, so that its folders are left alone; it is
 * never opened, never current, and never removed.
 *
 * Nothing is read before [load], and every change waits for it.
 *
 * @param newId draws a random id for a new profile.
 */
class DataStoreProfileRegistry private constructor(
    private val ledger: ProfileLedger,
    private val newId: () -> String,
) : SessionStore by CurrentProfileSession(ledger, newId),
    ProfileRecords by LedgerRecords(ledger),
    ProfileDataFormats by LedgerFormats(ledger),
    ProfileStore {

    constructor(dataStore: DataStore<Preferences>, cipher: TokenCipher, newId: () -> String) :
        this(ProfileLedger(dataStore, cipher), newId)

    override val current: StateFlow<Profile?> get() = ledger.current

    override val profiles: StateFlow<List<Profile>> get() = ledger.profiles

    override suspend fun load() = ledger.load()

    override suspend fun profile(id: String): Profile? = ledger.loaded().ready[id]

    override suspend fun updateCredential(id: String, credential: Credential, replacing: Credential): Profile? =
        ledger.changed { it.withCredential(id, credential, replacing, ledger.codec) }?.ready?.get(id)

    override suspend fun updateUser(id: String, user: UserProfile): Profile? =
        ledger.changed { it.withUser(id, user, ledger.codec) }?.ready?.get(id)

    override suspend fun switchTo(id: String) = ledger.change { it.switching(id) }

    override suspend fun needSignIn(id: String, refused: Credential): Profile? =
        ledger.changed { it.needingSignIn(id, refused, ledger.codec) }?.ready?.get(id)

    /** Who is current is looked at in the change itself: no switch can come between the look and the write. */
    override suspend fun removeSleeping(id: String): Boolean = ledger.changed { now ->
        // The reason is of a sign-out, which this never is: a profile that sleeps leaves somebody signed in.
        if (now.current?.id == id) null else now.removing(id, SignOutReason.UserRequest)
    } != null

    /**
     * The registry's part in the migration of an installation from before the profiles.
     *
     * @param sessionFile where that installation kept its one session.
     * @param registryFile this registry's own file: once it exists, the old session has been taken.
     */
    fun adoption(sessionFile: File, registryFile: File) = SessionAdoption(sessionFile, registryFile, ledger, newId)
}

/** What the registry lists beside the profiles that may be opened, as the file has it. */
private class LedgerRecords(private val ledger: ProfileLedger) : ProfileRecords {

    override suspend fun beingRemoved(): List<String> =
        ledger.loaded().stored.profiles.filter { it.state == StoredState.Removing }.map { it.id }

    override suspend fun beingMoved(): List<String> =
        ledger.loaded().stored.profiles.filter { it.state == StoredState.Moving }.map { it.id }

    override suspend fun forget(id: String) = ledger.change { it.forgetting(id) }

    override suspend fun listedIds(): Set<String>? = ledger.loaded().takeUnless { it.unreadable }?.listedIds()
}

/**
 * The session of the current profile. Which profile is current is looked at when a change is applied, not when
 * it is asked for, and a sign-out says which one it means: one that waited behind another finds that its
 * profile is no longer the current one, and removes nobody — least of all the profile that took its place.
 */
private class CurrentProfileSession(
    private val ledger: ProfileLedger,
    private val newId: () -> String,
) : SessionStore {

    override val state: StateFlow<SessionState> get() = ledger.state

    override val current: StateFlow<Profile?> get() = ledger.current

    override val serverUrl: StateFlow<String?> get() = ledger.serverUrl

    override val serverKind: StateFlow<ServerKind?> get() = ledger.serverKind

    /** Whoever is signed in: the address is the device's. The reason of a sign-out is answered by it. */
    override suspend fun setServerUrl(serverUrl: String, kind: ServerKind) = ledger.change { now ->
        val asItIs = now.serverUrl == serverUrl && now.serverKind == kind && now.reason == null
        if (asItIs) null else now.copy(serverUrl = serverUrl, serverKind = kind, reason = null)
    }

    /** What was replaced is read in the change itself: no other change can come between the look and the write. */
    override suspend fun save(session: Session, kind: ServerKind): ReplacedSession? {
        var replaced: ReplacedSession? = null
        ledger.changed(signedIn = true) { now ->
            replaced = now.sameAccount(session, kind)?.let { ReplacedSession(it.session.credential, it.signInNeeded) }
            now.saving(session, kind, newId, ledger.codec)
        }
        return replaced
    }

    override suspend fun clear(profileId: String, reason: SignOutReason): Boolean = ledger.changed { now ->
        if (now.current?.id == profileId) now.removing(profileId, reason) else null
    } != null
}
