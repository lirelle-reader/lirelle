package app.tfdn.lirelle.source.bookorbit.network.testing

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sessions in memory, as the registry keeps them: one profile for each server and account, p0, p1… as
 * they sign in, the one signed in last being current, and the one used before it taking its place when it is
 * signed out of.
 */
class InMemorySessionStore : SessionStore {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.SignedOut(serverUrl = null, reason = null))
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableCurrent = MutableStateFlow<Profile?>(null)
    override val current: StateFlow<Profile?> = mutableCurrent.asStateFlow()

    /** In order of use: the first is the current one. */
    private val profiles = mutableListOf<Profile>()
    private var nextId = 0
    private val changes = Mutex()

    val session: Session? get() = (state.value as? SessionState.SignedIn)?.session

    /**
     * Called by every [clear] before it looks at who is current: a test holds a sign-out there, as the real
     * store makes it wait behind the change before it.
     */
    var beforeClear: suspend () -> Unit = {}

    /**
     * Called by every [clear] once it has decided, before it returns: a test cancels the sign-out's caller there,
     * as the screen torn down by the profile switch does while the real store's write comes back.
     */
    var afterClear: suspend () -> Unit = {}

    private val mutableServerUrl = MutableStateFlow<String?>(null)
    override val serverUrl: StateFlow<String?> = mutableServerUrl.asStateFlow()

    private val mutableServerKind = MutableStateFlow<ServerKind?>(null)
    override val serverKind: StateFlow<ServerKind?> = mutableServerKind.asStateFlow()

    /** As the real store: remembered whoever is signed in, and told to a session that is signed out. */
    override suspend fun setServerUrl(serverUrl: String, kind: ServerKind) {
        mutableServerKind.value = kind
        mutableServerUrl.value = serverUrl
        if (state.value !is SessionState.SignedIn) mutableState.value = SessionState.SignedOut(serverUrl, null)
    }

    override suspend fun save(session: Session, kind: ServerKind): ReplacedSession? {
        val known = profiles.firstOrNull {
            it.kind == kind && it.session.serverUrl == session.serverUrl && it.session.user.id == session.user.id
        }
        profiles -= listOfNotNull(known).toSet()
        val saved = known?.copy(session = session, signInNeeded = false)
            ?: Profile("p${nextId++}", kind, session)
        profiles.add(0, saved)
        mutableServerKind.value = kind
        mutableServerUrl.value = session.serverUrl
        publish(state.value)
        return known?.let { ReplacedSession(it.session.credential, it.signInNeeded) }
    }

    /** A BookOrbit sign-in, which every one of these tests is. */
    suspend fun save(session: Session): ReplacedSession? = save(session, ServerKind.BookOrbit)

    /** Marks the profile [id] as the registry does when the server refused its session. */
    fun markSignInNeeded(id: String) {
        val index = profiles.indexOfFirst { it.id == id }
        profiles[index] = profiles[index].copy(signInNeeded = true)
        publish(state.value)
    }

    /** Like the real store: only the profile named, and only while it is the current one. */
    override suspend fun clear(profileId: String, reason: SignOutReason): Boolean {
        beforeClear()
        // One at a time, as the real store: the second decides from what the first left.
        val cleared = changes.withLock {
            val removed = profiles.firstOrNull()?.takeIf { it.id == profileId } ?: return@withLock false
            profiles -= removed
            if (profiles.isEmpty()) {
                mutableServerKind.value = removed.kind
                mutableServerUrl.value = removed.session.serverUrl
            }
            publish(SessionState.SignedOut(removed.session.serverUrl, reason))
            true
        }
        afterClear()
        return cleared
    }

    /** [signedOut] is what the session becomes when no profile is left. */
    private fun publish(signedOut: SessionState) {
        val profile = profiles.firstOrNull()
        mutableCurrent.value = profile
        mutableState.value = profile?.let { SessionState.SignedIn(it.session) } ?: signedOut
    }
}

/** The moment every test in this module treats as "now". */
val TestNow: Instant = Instant.parse("2026-11-13T08:00:00Z")

fun testTokens(
    accessToken: String = "access-1",
    accessTokenExpiresAt: Instant = TestNow.plusSeconds(900),
) = Tokens(
    accessToken = accessToken,
    accessTokenExpiresAt = accessTokenExpiresAt,
    refreshToken = "1".repeat(64),
    refreshTokenExpiresAt = TestNow.plusSeconds(7 * 24 * 3600L),
)

fun testSession(serverUrl: String, tokens: Tokens = testTokens()) = Session(
    serverUrl = serverUrl,
    credential = Credential.SessionTokens(sessionId = 42, tokens),
    user = UserProfile(id = "1", username = "reader", name = "Ann Reader", email = "ann@example.com"),
)

/** The tokens of a BookOrbit session, which every session of these tests is. */
val Session.tokens: Tokens get() = (credential as Credential.SessionTokens).tokens

/** The server's id for a BookOrbit session. */
val Session.sessionId: Long get() = (credential as Credential.SessionTokens).sessionId
