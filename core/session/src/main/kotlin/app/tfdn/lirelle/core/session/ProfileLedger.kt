package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The registry as it stands: what is stored, and what could be read of it.
 *
 * @property stored every profile listed, as written, in order of use; its `current` is a profile of [ready],
 *   or one said ready whose credential could not be read at this start, or null.
 * @property ready the profiles that may be opened, by id. A profile stored as ready and absent from here is
 *   one whose credential the keystore could not decrypt for now, or one holding a readable credential of another
 *   sort than its kind's ([ReadyEntry.OtherSort]): it is left as it is written, for the next start, until the same
 *   kind, server and account signs in again and takes it over ([saving]).
 * @property serverUrl the server remembered for the next sign-in: the last one typed and checked, signed in or
 *   not, or the one last signed in to or out of.
 * @property serverKind the kind of server at [serverUrl] ([SessionStore.serverKind]).
 * @property reason why the current profile was removed; of this process only, never stored.
 * @property unreadable the file could not be read at this start: [stored] is what was saved since, not what
 *   the device holds. Kept through every change, so that nothing is deleted for being absent from it.
 */
internal data class RegistrySnapshot(
    val stored: StoredRegistry = StoredRegistry(),
    val ready: Map<String, Profile> = emptyMap(),
    val serverUrl: String? = null,
    val serverKind: ServerKind? = null,
    val reason: SignOutReason? = null,
    val unreadable: Boolean = false,
) {
    /**
     * The profiles that may be opened, in order of use: the one written as current, then the others as they
     * are listed. One that could not be read at this start is not among them, wherever it is written.
     */
    val profiles: List<Profile>
        get() = (listOfNotNull(stored.current) + stored.profiles.map { it.id }).distinct().mapNotNull(ready::get)

    /**
     * The first of [profiles]: the one written as current, or, when that one could not be read at this start,
     * the one used last before it. The file then still names the one that could not be read as current, for
     * the start that reads it, but only until a change makes a profile current — a sign-in, a switch, the
     * current one removed while another is left — which writes that profile in its place; the one that could
     * not be read is from then on only listed, as used before the one shown in its place. Removing the last
     * profile that could be read leaves it named.
     */
    val current: Profile? get() = profiles.firstOrNull()

    fun sessionState(): SessionState =
        current?.let { SessionState.SignedIn(it.session) } ?: SessionState.SignedOut(serverUrl, reason)

    /**
     * [session], signed in to a server of [kind], goes to the profile of its kind, server and account, made when
     * the device has none that is ready, and that profile becomes the current one, beside the others, which stay
     * as they are. A new id is drawn again while it is one still listed: the folders of that name are another
     * profile's.
     *
     * The profile found may be one that needed to sign in: this is its account signing in again, and it no
     * longer does.
     *
     * A profile of that kind, server and account whose credential could not be read at this start ([unanswered])
     * — the keystore not answering, or a credential of another sort than its kind's — is taken over rather than
     * doubled: the credential just received replaces the one that could not be read, and what it kept on the
     * device is the same account's.
     */
    fun saving(session: Session, kind: ServerKind, newId: () -> String, codec: ProfileCodec): RegistrySnapshot {
        val known = sameAccount(session, kind)
        val profile = known?.copy(session = session, signInNeeded = false)
            ?: unanswered(session, kind)
            ?: Profile(generateSequence(newId).first { it !in listedIds() }, kind, session)
        return with(profile, codec).fronted(profile.id)
            .copy(serverUrl = session.serverUrl, serverKind = kind, reason = null)
    }

    /**
     * [credential] in the place of [replacing], which it was renewed from. Null, as for every change here, when
     * there is nothing to change — and when the profile no longer holds [replacing]: decided here, in the
     * change itself, so that no sign-in can come between the look and the write.
     */
    fun withCredential(id: String, credential: Credential, replacing: Credential, codec: ProfileCodec) =
        ready[id]?.takeIf { it.holds(replacing) }
            ?.let { with(it.copy(session = it.session.copy(credential = credential)), codec) }

    fun withUser(id: String, user: UserProfile, codec: ProfileCodec): RegistrySnapshot? {
        val profile = ready[id] ?: return null
        // An answer about another account says nothing about this one.
        if (profile.session.user.id != user.id || profile.session.user == user) return null
        return with(profile.copy(session = profile.session.copy(user = user)), codec)
    }

    /**
     * What an installation from before the profiles held becomes the registry's: its session a profile being
     * moved in, which is not current and is not opened until [settling]; without a session, only the server
     * it last used. What that installation kept, which the migration moves into the profile's folders, is of
     * the old format: it is emptied when the profile is opened. That installation knew BookOrbit alone.
     */
    fun adopting(old: OldSession, newId: () -> String, codec: ProfileCodec): RegistrySnapshot {
        val session = old.session
            ?: return old.serverUrl?.let { copy(serverUrl = it, serverKind = ServerKind.BookOrbit) } ?: this
        val id = generateSequence(newId).first { it !in listedIds() }
        val profile = Profile(id, ServerKind.BookOrbit, session, dataFormat = OldDataFormat)
        val moving = codec.encode(profile, StoredState.Moving)
        return copy(
            stored = stored.copy(profiles = stored.profiles + moving),
            serverUrl = session.serverUrl,
            serverKind = ServerKind.BookOrbit,
            reason = null,
        )
    }

    /**
     * The profile [id] has all its files: it is ready, and the current one. One whose session can never be
     * read again is, like any other such profile, to be removed. Null when [id] is not being moved in.
     *
     * @throws java.io.IOException when its credential could not be decrypted for now: it stays being moved in,
     *   with the files just given to it, for a start whose keystore answers.
     */
    fun settling(id: String, codec: ProfileCodec): RegistrySnapshot? {
        val moving = stored.profiles.firstOrNull { it.id == id && it.state == StoredState.Moving } ?: return null
        val profile = codec.decode(moving, certain = true)
        val state = if (profile == null) StoredState.Removing else StoredState.Ready
        val profiles = stored.profiles.map { if (it.id == id) it.copy(state = state) else it }
        val settled = copy(stored = stored.copy(profiles = profiles))
        if (profile == null) return settled
        return settled.fronted(id).copy(
            ready = ready + (id to profile),
            serverUrl = profile.session.serverUrl,
            serverKind = profile.kind,
            reason = null,
        )
    }

    /**
     * Already being removed: the first reason stands. Only a profile that may be opened is removed: one being
     * moved in is the migration's, which alone decides what becomes of it, and one whose credential could not
     * be read at this start is nobody's to remove before a start that can read it.
     *
     * When it was the current one, the profile used last before it becomes current. Only when none is left is
     * anybody signed out, and the server of the profile removed is then the one remembered, with [reason].
     */
    fun removing(id: String, reason: SignOutReason): RegistrySnapshot? {
        val removed = ready[id] ?: return null
        val wasCurrent = current?.id == id
        val profiles = stored.profiles.map { if (it.id == id) it.copy(state = StoredState.Removing) else it }
        val left = copy(stored = stored.copy(profiles = profiles), ready = ready - id)
        val next = left.profiles.firstOrNull()
        return when {
            !wasCurrent -> left
            next != null -> left.fronted(next.id)
            // A current profile that could not be read at this start is still the one written as such.
            else -> left.copy(
                stored = left.stored.copy(current = stored.current.takeIf { it != id }),
                serverUrl = removed.session.serverUrl,
                serverKind = removed.kind,
                reason = reason,
            )
        }
    }

    /** Only a profile being removed can be forgotten: the record of any other still names folders in use. */
    fun forgetting(id: String): RegistrySnapshot? {
        if (stored.profiles.none { it.id == id && it.state == StoredState.Removing }) return null
        return copy(stored = stored.copy(profiles = stored.profiles.filterNot { it.id == id }))
    }

    /** With the entries that could not be understood: their ids are taken, and their folders somebody's. */
    fun listedIds(): Set<String> =
        stored.profiles.mapTo(mutableSetOf()) { it.id } + stored.unread.mapNotNull { it.id }

    /** [profile] written where it is listed, or last when it is not: being written is not being used. */
    fun with(profile: Profile, codec: ProfileCodec): RegistrySnapshot {
        val written = codec.encode(profile)
        val profiles = if (stored.profiles.any { it.id == profile.id }) {
            stored.profiles.map { if (it.id == profile.id) written else it }
        } else {
            stored.profiles + written
        }
        return copy(stored = stored.copy(profiles = profiles), ready = ready + (profile.id to profile))
    }
}

/**
 * Keeps the registry in DataStore, and publishes the session of the current profile.
 *
 * Changes are applied one at a time, each decided from what the previous one published, so a late credential update
 * cannot bring back a profile that was removed. Every write completes before the new state is published.
 */
internal class ProfileLedger(private val dataStore: DataStore<Preferences>, cipher: TokenCipher) {

    val codec = ProfileCodec(cipher)
    private val mutex = Mutex()

    @Volatile
    private var snapshot = RegistrySnapshot()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val mutableCurrent = MutableStateFlow<Profile?>(null)
    val current: StateFlow<Profile?> = mutableCurrent.asStateFlow()
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = mutableProfiles.asStateFlow()
    private val mutableServerUrl = MutableStateFlow<String?>(null)

    /** The server remembered for the next sign-in, whoever is signed in. */
    val serverUrl: StateFlow<String?> = mutableServerUrl.asStateFlow()
    private val mutableServerKind = MutableStateFlow<ServerKind?>(null)

    /** The kind of [serverUrl]. */
    val serverKind: StateFlow<ServerKind?> = mutableServerKind.asStateFlow()

    /** Reads what is stored, once: a second call changes nothing. */
    suspend fun load() = mutex.withLock {
        if (mutableState.value != SessionState.Loading) return@withLock
        // An unreadable file must not leave the state at Loading: every caller waits on it.
        val loaded = try {
            read(dataStore.data.first())
        } catch (_: IOException) {
            // Signed out, but not empty: what the file lists is not known, and its folders are still theirs.
            RegistrySnapshot(unreadable = true)
        }
        publish(loaded)
    }

    /**
     * What the first read makes of [preferences]. A profile it had to give up is written as being removed
     * before anybody is told: its folders start to go as soon as this is published, and a start that could
     * read it after all — the keystore answering again — would otherwise open it over what is left of them.
     */
    private suspend fun read(preferences: Preferences): RegistrySnapshot {
        val written = preferences[ProfilesKey]?.let(codec::decodeRegistry) ?: StoredRegistry()
        val serverUrl = preferences[ServerUrlKey]
        val serverKind = when (val name = preferences[ServerKindKey]) {
            // Remembered without a kind: by a version that knew BookOrbit alone.
            null -> ServerKind.BookOrbit.takeIf { serverUrl != null }
            else -> ServerKind.entries.firstOrNull { it.name == name }
        }
        val read = sorted(written, serverUrl).copy(serverKind = serverKind)
        if (read.stored.profiles != written.profiles) {
            try {
                write(read)
            } catch (_: IOException) {
                // Signed out all the same: the next start decides again, from the same file.
            }
        }
        return read
    }

    private suspend fun write(next: RegistrySnapshot, pendingOidc: String? = null, signedIn: Boolean = false) {
        dataStore.edit {
            it[ProfilesKey] = codec.encode(next.stored)
            if (next.serverUrl != null) it[ServerUrlKey] = next.serverUrl
            // A kind this version does not know is left as it is written, with its address.
            if (next.serverKind != null) it[ServerKindKey] = next.serverKind.name
            if (signedIn) it.remove(PendingOidcKey)
            if (pendingOidc != null) it[PendingOidcKey] = pendingOidc
        }
    }

    /** The registry as it stands, once it has been loaded. */
    suspend fun loaded(): RegistrySnapshot {
        state.first { it != SessionState.Loading }
        return snapshot
    }

    /**
     * Applies [decide] once the registry is loaded, never at the same time as another change; null from it
     * changes nothing. A change that started is not interrupted when its caller is cancelled: what is written
     * is always published.
     */
    suspend fun change(decide: (RegistrySnapshot) -> RegistrySnapshot?) {
        changed(decide = decide)
    }

    /**
     * [change], for who needs what it made: the registry as this change left it, null when it changed nothing.
     *
     * @param pendingOidc a sign-in waiting for its redirect, as the old `session` file kept it, written in the
     *   same write as the change: the migration must not leave a registry's file that holds only that.
     * @param signedIn the change is a sign-in: an OIDC sign-in still waiting for its redirect is forgotten in
     *   the same write. Somebody has signed in since it was begun — with a password, or by its own redirect —
     *   and a browser tab finished later must not sign anybody else in, nor change who is current.
     */
    suspend fun changed(
        pendingOidc: String? = null,
        signedIn: Boolean = false,
        decide: (RegistrySnapshot) -> RegistrySnapshot?,
    ): RegistrySnapshot? {
        state.first { it != SessionState.Loading }
        return withContext(NonCancellable) {
            mutex.withLock {
                decide(snapshot)?.also {
                    write(it, pendingOidc, signedIn)
                    publish(it)
                }
            }
        }
    }

    /**
     * [change], for what is of this process only and changes nothing of the file: published, not written. Nothing
     * written can fail, and the next start reads the file as it was.
     */
    suspend fun changeUnwritten(decide: (RegistrySnapshot) -> RegistrySnapshot?) {
        state.first { it != SessionState.Loading }
        withContext(NonCancellable) {
            mutex.withLock { decide(snapshot)?.let(::publish) }
        }
    }

    /**
     * The profiles first, the current one among them, then the session: whoever is woken by a session signed
     * in finds the profile it belongs to, and whoever is woken by a current profile finds it listed. The
     * remembered address before them all: a session signed out is never seen with another address than it.
     */
    private fun publish(next: RegistrySnapshot) {
        snapshot = next
        // The kind before its address: whoever reads a new address finds its kind.
        mutableServerKind.value = next.serverKind
        mutableServerUrl.value = next.serverUrl
        mutableProfiles.value = next.profiles
        mutableCurrent.value = next.current
        mutableState.value = next.sessionState()
    }

    /**
     * A profile said ready whose session can never be read again — the key that encrypted its credential is
     * gone, or it is not what that key encrypted — is never opened: it is listed as being removed, so that its
     * folders go. One whose credential could not be decrypted for now — the keystore not answering — is not
     * opened at this start either, but nothing of it is touched: marking it would delete, for a failure of a moment,
     * everything the account kept on the device. It stays ready, and written as current if it was, for the
     * next start; at this one, the profile used last before it is shown ([RegistrySnapshot.current]).
     * One whose credential is readable but of another sort than its kind's ([ReadyEntry.OtherSort]) is likewise
     * kept as it is written and not opened. An entry that could not be understood at all is not a profile
     * here, and is left as it is. A registry whose text is not one lists nothing.
     */
    private fun sorted(stored: StoredRegistry, serverUrl: String?): RegistrySnapshot {
        val withheld = mutableSetOf<String>()
        val ready = stored.profiles
            .filter { it.state == StoredState.Ready }
            .mapNotNull { entry ->
                val read = try {
                    codec.decodeReady(entry)
                } catch (_: IOException) {
                    null
                }
                if (read == null || read == ReadyEntry.OtherSort) withheld += entry.id
                (read as? ReadyEntry.Opened)?.profile
            }
            .associateBy { it.id }
        val kept = ready.keys + withheld
        val profiles = stored.profiles.map {
            if (it.state == StoredState.Ready && it.id !in kept) it.copy(state = StoredState.Removing) else it
        }
        return RegistrySnapshot(
            stored = stored.copy(profiles = profiles, current = stored.current?.takeIf { it in kept }),
            ready = ready,
            serverUrl = serverUrl,
        )
    }

    private companion object {
        val ProfilesKey = stringPreferencesKey("profiles")
        val ServerUrlKey = stringPreferencesKey("server_url")
        val ServerKindKey = stringPreferencesKey("server_kind")
    }
}

/**
 * The ready profile of [kind], [session]'s server and account, whose session [RegistrySnapshot.saving] replaces;
 * null when there is none. An account id is the server's own, and means nothing to a server of another kind.
 */
internal fun RegistrySnapshot.sameAccount(session: Session, kind: ServerKind): Profile? = ready.values.firstOrNull {
    it.kind == kind && it.session.serverUrl == session.serverUrl && it.session.user.id == session.user.id
}

/**
 * The profile of [kind], [session]'s server and account that is said ready and could not be opened at this
 * start, with [session] for its own; null when there is none. Its kind, server and account are stored in clear:
 * only its credential needed the keystore. One withheld at this start ([withholding]) is one of them. It keeps
 * the format of its data: what its folders hold is not changed by a sign-in.
 */
private fun RegistrySnapshot.unanswered(session: Session, kind: ServerKind): Profile? {
    val entry = stored.profiles.firstOrNull {
        it.state == StoredState.Ready && it.id !in ready && it.kind == kind.name &&
            it.session.serverUrl == session.serverUrl && it.session.user.id == session.user.id
    } ?: return null
    return Profile(entry.id, kind, session, dataFormat = entry.dataFormat ?: OldDataFormat)
}
