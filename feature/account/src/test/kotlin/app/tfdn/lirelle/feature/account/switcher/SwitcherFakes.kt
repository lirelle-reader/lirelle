package app.tfdn.lirelle.feature.account.switcher

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.ui.ProfileAvatarModel
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal fun profile(
    id: String,
    username: String,
    server: String,
    signInNeeded: Boolean = false,
    email: String? = null,
) = Profile(
    id = id,
    kind = ServerKind.BookOrbit,
    session = bookOrbitSession(
        serverUrl = server,
        sessionId = 1,
        tokens = Tokens("access-$id", Instant.MAX, "refresh-$id", Instant.MAX),
        user = UserProfile("1", username, "Name of $username", email = email),
    ),
    signInNeeded = signInNeeded,
)

/** A Komga profile: its account named by its email, and a key of its own. */
internal fun komgaProfile(id: String, email: String, server: String) = Profile(
    id = id,
    kind = ServerKind.Komga,
    session = Session(
        serverUrl = server,
        credential = Credential.ApiKey(key = "key-$id", keyId = "key-id-$id"),
        user = UserProfile(email, email, email, email = email),
    ),
)

/** A row as the view model makes it, for the screens' tests, which need no registry. */
internal fun row(
    id: String,
    username: String,
    server: String,
    current: Boolean = false,
    signInNeeded: Boolean = false,
) = ProfileRow(
    id = id,
    line = "$username · BookOrbit",
    username = username,
    kindName = "BookOrbit",
    kind = ServerKind.BookOrbit,
    server = server,
    avatar = ProfileAvatarModel(id, username.take(1).uppercase(), "B", picture = null, signInNeeded = signInNeeded),
    current = current,
    signInNeeded = signInNeeded,
)

/** The row of a Komga profile, as the view model makes it. */
internal fun komgaRow(id: String, username: String, server: String, current: Boolean = false) =
    row(id, username, server, current).let {
        it.copy(
            line = "$username · Komga",
            kindName = "Komga",
            kind = ServerKind.Komga,
            avatar = it.avatar.copy(kindLetter = "K"),
        )
    }

/** Camille is current; Sacha reads comics elsewhere; the test account waits for a sign-in. */
internal val ThreeProfiles = SwitcherState(
    listOf(
        row("p0", "camille", "livres.example.org", current = true),
        row("p1", "sacha", "bd.example.org"),
        row("p2", "test", "livres.example.org", signInNeeded = true),
    ),
)

/**
 * The registry as the profile button and the Profiles page use it: the profiles in order of use, the first
 * being the current one, the switch, and the removal. They ask nothing else of it, and a test fails if they do.
 */
internal class FakeProfiles(vararg listed: Profile) : ProfileStore {
    override val profiles = MutableStateFlow(listed.toList())
    override val current = MutableStateFlow(listed.firstOrNull())

    /** One entry per removal done, in order. */
    val removals = mutableListOf<Pair<String, SignOutReason>>()

    /**
     * When set, a removal waits for this before it looks at who is current: as the real registry makes it wait
     * behind the change before it — a switch, a sign-in.
     */
    var gate: CompletableDeferred<Unit>? = null

    /** As the registry does: decided when the change is applied, and only a profile that sleeps then. */
    override suspend fun removeSleeping(id: String): Boolean {
        gate?.await()
        if (current.value?.id == id || profiles.value.none { it.id == id }) return false
        removals += id to SignOutReason.UserRequest
        profiles.value = profiles.value.filterNot { it.id == id }
        return true
    }

    /** What a sign-in that succeeded leaves: [profile] is listed, and the current one. */
    fun signedIn(profile: Profile) {
        profiles.value = listOf(profile) + profiles.value.filterNot { it.id == profile.id }
        current.value = profile
    }

    /** As the registry does: the profile comes first, and is the current one. */
    override suspend fun switchTo(id: String) {
        val chosen = profiles.value.first { it.id == id }
        profiles.value = listOf(chosen) + profiles.value.filterNot { it.id == id }
        current.value = chosen
    }

    override suspend fun load() = Unit

    override suspend fun profile(id: String): Profile? = notAsked()

    override suspend fun updateCredential(id: String, credential: Credential, replacing: Credential): Profile? =
        notAsked()

    override suspend fun updateUser(id: String, user: UserProfile): Profile? = notAsked()

    override suspend fun needSignIn(id: String, refused: Credential): Profile? = notAsked()

    override suspend fun beingRemoved(): List<String> = notAsked()

    override suspend fun beingMoved(): List<String> = notAsked()

    override suspend fun forget(id: String): Unit = notAsked()

    override suspend fun listedIds(): Set<String>? = notAsked()

    private fun notAsked(): Nothing = error("The lists of profiles only list, switch and remove one that sleeps")
}
