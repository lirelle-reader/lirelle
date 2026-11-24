package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.CurrentDataFormat
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileDataFormats
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

internal const val Server = "https://books.example.com"

internal fun session(userId: Long = 1) = Session(
    serverUrl = Server,
    credential = credential(userId),
    user = UserProfile("$userId", "user$userId", "User $userId", email = null),
)

/** The BookOrbit tokens of [session] for [userId], with its session id; [access] when renewed. */
internal fun credential(userId: Long = 1, access: String = "access-$userId") =
    Credential.SessionTokens(userId, Tokens(access, Instant.MAX, "refresh-$userId", Instant.MAX))

internal fun profile(id: String, userId: Long = 1, dataFormat: Int = CurrentDataFormat) =
    Profile(id, ServerKind.BookOrbit, session(userId), dataFormat = dataFormat)

/**
 * The registry in memory, publishing as the real one does: the profile first, then the session. Ids are p0,
 * p1… Nothing is stored before [load]: [stored], [leftBeingRemoved] and [leftBeingMoved] say what it finds, or
 * [unreadable] that it could not read what was there. [alsoListed] are ready profiles beside the current one,
 * used before it, in order of use. A profile withheld at this start ([withhold]) is kept in [withheld], listed and
 * neither opened nor current, as the real one keeps it.
 */
internal class FakeRegistry(
    private val stored: Profile? = null,
    private val leftBeingRemoved: List<String> = emptyList(),
    private val unreadable: Boolean = false,
    private val leftBeingMoved: List<String> = emptyList(),
    private val alsoListed: List<Profile> = emptyList(),
) : SessionStore, ProfileStore, ProfileDataFormats {

    override val state = MutableStateFlow<SessionState>(SessionState.Loading)
    override val current = MutableStateFlow<Profile?>(null)
    override val profiles = MutableStateFlow<List<Profile>>(emptyList())
    override val serverUrl = MutableStateFlow<String?>(null)

    /** Called with the id when a profile is forgotten, before it is. */
    var onForget: (String) -> Unit = {}

    /** Called once the listed ids were taken, before they are given: what happens meanwhile is not in them. */
    var onListedIds: () -> Unit = {}

    /** In order of use: the first is the current one. */
    private val ready = mutableListOf<Profile>()
    private val removing = mutableListOf<String>()
    private var nextId = 0

    /** The profiles withheld at this start, as they were when they were. */
    val withheld = mutableListOf<Profile>()

    override suspend fun load() {
        if (state.value != SessionState.Loading) return
        removing += leftBeingRemoved
        ready += listOfNotNull(stored) + alsoListed
        publish(SessionState.SignedOut(serverUrl = null, reason = null))
    }

    override val serverKind = MutableStateFlow<ServerKind?>(null)

    /** As the real one: remembered whoever is signed in, and told to a session that is signed out. */
    override suspend fun setServerUrl(serverUrl: String, kind: ServerKind) {
        serverKind.value = kind
        this.serverUrl.value = serverUrl
        if (current.value == null) state.value = SessionState.SignedOut(serverUrl, reason = null)
    }

    /** As the real one: the account's profile, made when it has none, becomes current beside the others. */
    override suspend fun save(session: Session, kind: ServerKind): ReplacedSession? {
        val known = ready.firstOrNull {
            it.kind == kind && it.session.serverUrl == session.serverUrl && it.session.user.id == session.user.id
        }
        val saved = known?.copy(session = session, signInNeeded = false)
            ?: Profile("p${nextId++}", kind, session)
        ready.removeAll { it.id == saved.id }
        ready.add(0, saved)
        serverKind.value = kind
        serverUrl.value = session.serverUrl
        publish(state.value)
        return known?.let { ReplacedSession(it.session.credential, it.signInNeeded) }
    }

    /** A BookOrbit sign-in, which every one of these tests is. */
    suspend fun save(session: Session): ReplacedSession? = save(session, ServerKind.BookOrbit)

    /** As the real one: only the profile named, and only while it is the current one. */
    override suspend fun clear(profileId: String, reason: SignOutReason): Boolean {
        if (current.value?.id != profileId) return false
        removed(profileId, reason)
        return true
    }

    override suspend fun profile(id: String): Profile? = ready.firstOrNull { it.id == id }

    /** As the real one: only while the profile still holds the sign-in the renewal was made from. */
    override suspend fun updateCredential(id: String, credential: Credential, replacing: Credential) =
        if (holds(id, replacing)) change(id) { it.copy(session = it.session.copy(credential = credential)) } else null

    override suspend fun updateUser(id: String, user: UserProfile) =
        change(id) { it.copy(session = it.session.copy(user = user)) }

    override suspend fun switchTo(id: String) {
        val wanted = profile(id)?.takeIf { it != ready.first() } ?: return
        ready -= wanted
        ready.add(0, wanted)
        publish(state.value)
    }

    /** As the real one: only while the profile still holds the sign-in the server refused. */
    override suspend fun needSignIn(id: String, refused: Credential): Profile? =
        if (profile(id)?.signInNeeded == false && holds(id, refused)) {
            change(id) { it.copy(signInNeeded = true) }
        } else {
            null
        }

    private suspend fun holds(id: String, credential: Credential) =
        profile(id)?.session?.credential?.isSameSignIn(credential) == true

    /**
     * What both removals do, each once it has looked at who is current. As the real one: the profile used last
     * before the current one takes its place, when there is one.
     */
    private suspend fun removed(id: String, reason: SignOutReason) {
        val removed = profile(id) ?: return
        ready -= removed
        removing += id
        if (ready.isEmpty()) {
            serverKind.value = removed.kind
            serverUrl.value = removed.session.serverUrl
        }
        publish(SessionState.SignedOut(removed.session.serverUrl, reason))
    }

    /** As the real one: only while the profile is not the current one. */
    override suspend fun removeSleeping(id: String): Boolean {
        if (current.value?.id == id || profile(id) == null) return false
        removed(id, SignOutReason.UserRequest)
        return true
    }

    override suspend fun beingRemoved(): List<String> = removing.toList()

    override suspend fun beingMoved(): List<String> = leftBeingMoved

    override suspend fun forget(id: String) {
        if (id !in removing) return
        onForget(id)
        removing -= id
    }

    override suspend fun listedIds(): Set<String>? {
        val listed = if (unreadable) {
            null
        } else {
            (ready.map { it.id } + withheld.map { it.id } + removing + leftBeingMoved).toSet()
        }
        onListedIds()
        return listed
    }

    /** The format of a profile whose data started again cannot be written: the registry's file is not. */
    var failFormatWrite = false

    override suspend fun dataStartedAgain(id: String) {
        if (failFormatWrite) throw IOException("cannot write")
        change(id) { it.copy(dataFormat = CurrentDataFormat) }
    }

    /** As the real one: out of the ready profiles for this start, its record as it was. */
    override suspend fun withhold(id: String) {
        val profile = profile(id) ?: return
        ready -= profile
        withheld += profile
        publish(SessionState.SignedOut(serverUrl.value, reason = null))
    }

    private fun change(id: String, transform: (Profile) -> Profile): Profile? {
        val at = ready.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        ready[at] = transform(ready[at])
        publish(state.value)
        return ready[at]
    }

    /** [signedOut] is what the session becomes when no profile is left. */
    private fun publish(signedOut: SessionState) {
        val profile = ready.firstOrNull()
        profiles.value = ready.toList()
        current.value = profile
        state.value = profile?.let { SessionState.SignedIn(it.session) } ?: signedOut
    }
}
