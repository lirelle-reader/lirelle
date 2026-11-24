package app.tfdn.lirelle.source.komga.testing

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.ProfileSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A Komga profile's session held in memory, behaving like the real one: a write is in [session] when it returns,
 * and an answer about a key the profile no longer holds changes nothing. The test sets [session] directly to sign
 * in again.
 */
class FakeProfileSession(initial: Session) : ProfileSession {
    override val profileId = "p1"
    override val kind = ServerKind.Komga
    override val session = MutableStateFlow(initial)
    override val signInNeeded = MutableStateFlow(false)
    override val open: Boolean = true

    override suspend fun updateCredential(credential: Credential, replacing: Credential) {
        if (holds(replacing)) session.update { it.copy(credential = credential) }
    }

    override suspend fun updateUser(user: UserProfile) {
        session.update { it.copy(user = user) }
    }

    override suspend fun expire(refused: Credential) {
        if (holds(refused)) signInNeeded.value = true
    }

    private fun holds(credential: Credential) = session.value.credential.isSameSignIn(credential)
}

/** A Komga session of Ann's account on [serverUrl], with [key]. */
fun komgaSession(serverUrl: String, key: Credential.ApiKey = Credential.ApiKey(TheKey, TheKeyId)) = Session(
    serverUrl = serverUrl,
    credential = key,
    user = UserProfile("0B5Y2S1ZK4N7Q", "ann@example.com", "ann@example.com", "ann@example.com"),
)
