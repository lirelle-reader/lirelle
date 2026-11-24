package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.clearCurrent
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoreProfileSessionTest {

    private val registry = FakeRegistry(stored = profile("p7", userId = 1))

    /** The tokens the profile holds at first, and what the server makes of them when it renews them. */
    private val held = credential(userId = 1)
    private val renewed = credential(userId = 1, access = "access-renewed")

    /** The same account signed in again: a new session, whose refresh token is another. */
    private val again = session(userId = 1).copy(
        credential = Credential.SessionTokens(1, Tokens("access-again", Instant.MAX, "refresh-again", Instant.MAX)),
    )

    @Test
    fun `it follows the session of its own profile`() = runTest(UnconfinedTestDispatcher()) {
        registry.load()
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)

        own.updateCredential(renewed, replacing = held)

        assertThat(own.session.value).isEqualTo(session(userId = 1).copy(credential = renewed))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(own.session.value))
    }

    @Test
    fun `another profile becoming current leaves it as it was, and its writes go nowhere else`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        registry.load()
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        registry.clearCurrent(SignOutReason.UserRequest)
        registry.save(session(userId = 2))

        // A call that was in flight when its profile closed ends now.
        own.updateCredential(renewed, replacing = held)

        assertThat(own.session.value).isEqualTo(session(userId = 1))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session(userId = 2)))
    }

    @Test
    fun `a session the server no longer renews marks its profile, which stays current and open`() = runTest {
        registry.load()
        // What follows the store never runs here: the mark is known as soon as the expiry returns.
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        assertThat(own.signInNeeded.value).isFalse()

        own.expire(held)

        assertThat(own.signInNeeded.value).isTrue()
        assertThat(own.open).isTrue()
        assertThat(registry.current.value).isEqualTo(profile("p7", userId = 1).copy(signInNeeded = true))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session(userId = 1)))
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(own.session.value).isEqualTo(session(userId = 1))
    }

    /** A renewal that was in flight when the user signed out, and that the logout makes fail. */
    @Test
    fun `a session that expires once its profile was removed marks nobody`() = runTest {
        registry.load()
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        registry.clearCurrent(SignOutReason.UserRequest)

        own.expire(held)

        assertThat(own.signInNeeded.value).isFalse()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        assertThat(registry.profiles.value).isEmpty()
    }

    /** A renewal held the old refresh token for seconds; the account signed in again before its 401 came. */
    @Test
    fun `a refusal about the tokens of before a new sign-in marks nobody, and leaves the new session`() = runTest {
        registry.load()
        // What follows the store never runs here: the new session is known as soon as the expiry returns.
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        registry.save(again)

        own.expire(held)

        assertThat(own.signInNeeded.value).isFalse()
        assertThat(registry.profile("p7")?.signInNeeded).isFalse()
        assertThat(own.session.value).isEqualTo(again)
    }

    @Test
    fun `a renewal of the tokens of before a new sign-in writes nothing, and leaves the new session`() = runTest {
        registry.load()
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        registry.save(again)

        own.updateCredential(renewed, replacing = held)

        assertThat(registry.profile("p7")?.session).isEqualTo(again)
        assertThat(own.session.value).isEqualTo(again)
    }

    @Test
    fun `a profile stored as needing sign-in opens marked`() = runTest {
        val marked = profile("p7", userId = 1).copy(signInNeeded = true)

        val own = StoreProfileSession(marked, FakeRegistry(stored = marked).also { it.load() }, backgroundScope)

        assertThat(own.signInNeeded.value).isTrue()
    }

    @Test
    fun `the same account signing in again gives it the new tokens and clears the mark`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        registry.load()
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        own.expire(held)
        // The mark is never seen down beside the tokens the server refused.
        val seen = mutableListOf<Pair<Boolean, Credential>>()
        backgroundScope.launch { own.signInNeeded.collect { seen += it to own.session.value.credential } }

        registry.save(session(userId = 1).copy(credential = renewed))

        assertThat(own.session.value.credential).isEqualTo(renewed)
        assertThat(own.signInNeeded.value).isFalse()
        assertThat(own.open).isTrue()
        assertThat(seen).containsExactly(true to session(userId = 1).credential, false to renewed).inOrder()
    }

    @Test
    fun `another account signing in leaves it marked, with its own tokens`() = runTest(UnconfinedTestDispatcher()) {
        registry.load()
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        own.expire(held)

        registry.save(session(userId = 2))

        assertThat(own.signInNeeded.value).isTrue()
        assertThat(own.session.value).isEqualTo(session(userId = 1))
        assertThat(registry.profile("p7")?.signInNeeded).isTrue()
    }

    @Test
    fun `what it wrote is its session as soon as the write returns`() = runTest {
        registry.load()
        // What follows the store has not run yet, and is not waited for: nothing here lets it run.
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)

        own.updateCredential(renewed, replacing = held)
        assertThat(own.session.value.credential).isEqualTo(renewed)

        val renamed = session(userId = 1).user.copy(name = "Renamed")
        own.updateUser(renamed)
        assertThat(own.session.value).isEqualTo(session(userId = 1).copy(credential = renewed, user = renamed))
    }

    @Test
    fun `a write that changed nothing leaves its session as it was`() = runTest {
        registry.load()
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        registry.clearCurrent(SignOutReason.UserRequest)

        own.updateCredential(renewed, replacing = held)

        assertThat(own.session.value).isEqualTo(session(userId = 1))
    }

    @Test
    fun `it is open until its profile is removed, which it knows before anything followed the store`() = runTest {
        registry.load()
        // What follows the store never runs here: the store itself is asked.
        val own = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        assertThat(own.open).isTrue()

        registry.clearCurrent(SignOutReason.UserRequest)

        assertThat(own.open).isFalse()
    }

    @Test
    fun `it is no longer open once its objects were thrown away, though still the current profile`() = runTest {
        registry.load()
        val life = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val own = StoreProfileSession(profile("p7", userId = 1), registry, life)
        assertThat(own.open).isTrue()

        life.cancel()

        assertThat(own.open).isFalse()
    }

    @Test
    fun `what the store told late does not bring back the session of before a write`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        registry.load()
        // The store's news as they reach a follower that is behind: told here by hand, after the write.
        val news = MutableStateFlow<Profile?>(null)
        val store = object : ProfileStore by registry {
            override val current = news
        }
        val own = StoreProfileSession(profile("p7", userId = 1), store, backgroundScope)
        own.updateCredential(renewed, replacing = held)

        news.value = profile("p7", userId = 1)

        assertThat(own.session.value.credential).isEqualTo(renewed)
    }
}
