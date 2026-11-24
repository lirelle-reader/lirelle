package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

internal const val CamilleServer = "https://books.example.com"
internal const val SachaServer = "https://bd.example.org"

internal fun session(userId: Long, username: String, server: String) = bookOrbitSession(
    serverUrl = server,
    sessionId = userId,
    tokens = Tokens("access-$userId", Instant.MAX, "refresh-$userId", Instant.MAX),
    user = UserProfile("$userId", username, "Name of $username", email = null),
)

internal fun profile(id: String, userId: Long, username: String, server: String, signInNeeded: Boolean = false) =
    Profile(id, ServerKind.BookOrbit, session(userId, username, server), signInNeeded)

/** The profile the sign-in screens of these tests are opened over. */
internal val Camille = profile("p0", userId = 1, username = "camille", server = CamilleServer)

/** Another profile of the device, on another server, whose session expired. */
internal val Sacha = profile("p1", userId = 2, username = "sacha", server = SachaServer, signInNeeded = true)

/**
 * The registry as the sign-in screens read it: the profiles listed, the first being the current one. They ask
 * nothing else of it, and a test fails if they do.
 */
internal class FakeProfiles(vararg listed: Profile) : ProfileStore {
    override val profiles = MutableStateFlow(listed.toList())
    override val current = MutableStateFlow(listed.firstOrNull())

    /** What a sign-in that succeeded leaves: [profile] is listed, and the current one. */
    fun signedIn(profile: Profile) {
        profiles.value = listOf(profile) + profiles.value.filterNot { it.id == profile.id }
        current.value = profile
    }

    override suspend fun load() = Unit

    override suspend fun profile(id: String): Profile? = notAsked()

    override suspend fun updateCredential(id: String, credential: Credential, replacing: Credential): Profile? =
        notAsked()

    override suspend fun updateUser(id: String, user: UserProfile): Profile? = notAsked()

    override suspend fun switchTo(id: String): Unit = notAsked()

    override suspend fun needSignIn(id: String, refused: Credential): Profile? = notAsked()

    override suspend fun removeSleeping(id: String): Boolean = notAsked()

    override suspend fun beingRemoved(): List<String> = notAsked()

    override suspend fun beingMoved(): List<String> = notAsked()

    override suspend fun forget(id: String): Unit = notAsked()

    override suspend fun listedIds(): Set<String>? = notAsked()

    private fun notAsked(): Nothing = error("The sign-in screens change nothing of the registry by themselves")
}
