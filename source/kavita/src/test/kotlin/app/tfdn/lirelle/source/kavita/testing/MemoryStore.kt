package app.tfdn.lirelle.source.kavita.testing

import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow

/** The registry as a sign-in sees it: what was saved, with its kind, and a save that can be made to fail. */
class MemoryStore : SessionStore {
    override val state = MutableStateFlow<SessionState>(SessionState.SignedOut(serverUrl = null, reason = null))
    override val current = MutableStateFlow<Profile?>(null)
    override val serverUrl = MutableStateFlow<String?>(null)
    override val serverKind = MutableStateFlow<ServerKind?>(null)

    val saved = mutableListOf<Pair<Session, ServerKind>>()
    val cleared = mutableListOf<String>()

    /** The next save fails, as a registry that cannot be written. */
    var failSaves = false

    /** What a save answers it replaced: the credential of the account's profile, when it was added again. */
    var replaced: ReplacedSession? = null

    override suspend fun setServerUrl(serverUrl: String, kind: ServerKind) {
        this.serverUrl.value = serverUrl
        serverKind.value = kind
    }

    override suspend fun save(session: Session, kind: ServerKind): ReplacedSession? {
        if (failSaves) throw IOException("cannot write")
        saved += session to kind
        current.value = Profile("p${saved.size}", kind, session)
        state.value = SessionState.SignedIn(session)
        return replaced
    }

    /**
     * Called by every [clear] once it has removed the profile, before it returns: a test cancels the sign-out's
     * caller there, as the screen torn down by the profile switch does while the real store's write comes back.
     */
    var afterClear: suspend () -> Unit = {}

    override suspend fun clear(profileId: String, reason: SignOutReason): Boolean {
        if (current.value?.id != profileId) return false
        cleared += profileId
        current.value = null
        state.value = SessionState.SignedOut(serverUrl.value, reason)
        afterClear()
        return true
    }
}
