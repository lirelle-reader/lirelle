package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What a screen is told of the open profile's sign-in is what the profile's session says, as it changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSignInTest {

    private val registry = FakeRegistry(stored = profile("p7", userId = 1))

    @Test
    fun `it names the profile and its account as they are when asked`() = runTest(UnconfinedTestDispatcher()) {
        registry.load()
        val session = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
        val signIn = ProfileSignIn(session)
        val account = session.session.value.user

        assertThat(signIn.profileId).isEqualTo("p7")
        assertThat(signIn.kind).isEqualTo(ServerKind.BookOrbit)
        assertThat(signIn.username).isEqualTo(account.username)

        // The account was renamed on the server, and read again.
        session.updateUser(account.copy(username = "renamed"))

        assertThat(signIn.username).isEqualTo("renamed")
    }

    @Test
    fun `it waits for a sign-in when the session expired, and no longer once the account signed in again`() =
        runTest(UnconfinedTestDispatcher()) {
            registry.load()
            val session = StoreProfileSession(profile("p7", userId = 1), registry, backgroundScope)
            val signIn = ProfileSignIn(session)
            assertThat(signIn.needed.value).isFalse()

            session.expire(session.session.value.credential)
            assertThat(signIn.needed.value).isTrue()

            registry.save(session(userId = 1))
            assertThat(signIn.needed.value).isFalse()
        }
}
