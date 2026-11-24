package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.session.Sample.Server
import app.tfdn.lirelle.core.session.Sample.otherSession
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A keystore that does not answer at a start is not an account that left: the profile is not opened, and
 * nothing it keeps on the device is given up.
 */
class ProfileRegistryKeystoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    private fun TestScope.dataStore() = GatedDataStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") },
    )

    /** A new registry over the same store, as after a process restart. Ids go on: p0, p1… */
    private suspend fun loaded(dataStore: DataStore<Preferences>, cipher: TokenCipher = FakeTokenCipher()) =
        DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }.also { it.load() }

    private val silentKeystore get() = FakeTokenCipher(failingForNow = true)

    /** A third account, with tokens of its own: the keystore can be silent for it alone. */
    private val third = session.copyCredential(
        sessionId = 44,
        tokens = session.tokens.copy(accessToken = "access-3", refreshToken = "refresh-3"),
    ).copy(
        user = UserProfile(id = "3", username = "third", name = "Cid Third", email = null),
    )

    /** A keystore that does not answer for the tokens of [silentFor], and reads every other account's. */
    private fun silentFor(silentFor: Session) = object : TokenCipher by FakeTokenCipher() {
        private val real = FakeTokenCipher()

        override fun decryptOrThrow(cipherText: String): String? = real.decrypt(cipherText)?.also {
            if (silentFor.tokens.accessToken in it) throw IOException("The keystore failed")
        }
    }

    @Test
    fun `a profile whose tokens cannot be read for now is not opened and not removed`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        val writes = dataStore.writes

        val registry = loaded(dataStore, silentKeystore)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.current.value).isNull()
        assertThat(registry.profile("p0")).isNull()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.listedIds()).containsExactly("p0")
        assertThat(dataStore.writes).isEqualTo(writes)
    }

    @Test
    fun `it is opened again at the next start, whatever was written meanwhile`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        val registry = loaded(dataStore, silentKeystore)
        // What the sign-in screen and a late sign-out do while nobody is signed in.
        registry.setServerUrl("https://other.example.com")
        assertThat(registry.clear("p0", SignOutReason.UserRequest)).isFalse()
        registry.remove("p0", SignOutReason.UserRequest)

        val restarted = loaded(dataStore)

        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(restarted.current.value?.id).isEqualTo("p0")
        assertThat(restarted.beingRemoved()).isEmpty()
    }

    @Test
    fun `signing in to the same account at that start takes the profile over`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        val registry = loaded(dataStore, silentKeystore)
        val again = session.copyCredential(sessionId = 77, tokens = session.tokens.copy(accessToken = "access-2"))

        registry.save(again)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(again))
        assertThat(registry.current.value?.id).isEqualTo("p0")
        assertThat(registry.listedIds()).containsExactly("p0")
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(again))
        assertThat(restarted.listedIds()).containsExactly("p0")
    }

    @Test
    fun `signing in to another account at that start keeps the profile that was not opened`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        val registry = loaded(dataStore, silentKeystore)

        registry.save(otherSession)

        // It is neither listed nor to be removed at this start, and is there again at one that can read it.
        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertThat(registry.profiles.value.map { it.id }).containsExactly("p1")
        assertThat(registry.beingRemoved()).isEmpty()
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(restarted.profile("p0")?.session).isEqualTo(session)
        assertThat(restarted.profiles.value.map { it.id }).containsExactly("p1", "p0").inOrder()
        assertThat(restarted.beingRemoved()).isEmpty()
    }

    @Test
    fun `signing in to its account takes it over among the others, without a second profile`() = runTest {
        val dataStore = dataStore()
        val first = loaded(dataStore)
        listOf(session, otherSession, third).forEach { first.save(it) }
        first.switchTo("p0")
        // p0, p2, p1 in order of use, and the keystore does not answer for p2.
        val registry = loaded(dataStore, silentFor(third))
        assertThat(registry.profiles.value.map { it.id }).containsExactly("p0", "p1").inOrder()
        val again = third.copyCredential(sessionId = 77, tokens = third.tokens.copy(accessToken = "access-again"))

        registry.save(again)

        assertThat(registry.current.value).isEqualTo(Profile("p2", ServerKind.BookOrbit, again))
        assertThat(registry.profiles.value.map { it.id }).containsExactly("p2", "p0", "p1").inOrder()
        assertThat(registry.listedIds()).containsExactly("p0", "p1", "p2")
        assertThat(registry.beingRemoved()).isEmpty()
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(again))
        assertThat(restarted.profiles.value.map { it.id }).containsExactly("p2", "p0", "p1").inOrder()
        assertThat(restarted.listedIds()).containsExactly("p0", "p1", "p2")
    }

    @Test
    fun `removing the last profile that can be read signs out and leaves the one that cannot current`() = runTest {
        val dataStore = dataStore()
        val first = loaded(dataStore)
        first.save(session.copy(serverUrl = OtherServer))
        first.save(third)
        // p1 is the current one, and the keystore does not answer for it: p0 is shown in its place.
        val registry = loaded(dataStore, silentFor(third))
        assertThat(registry.current.value?.id).isEqualTo("p0")

        assertThat(registry.clear("p0", SignOutReason.UserRequest)).isTrue()

        // Signed out, at the address of the profile that was shown and removed.
        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(OtherServer, SignOutReason.UserRequest))
        assertThat(registry.current.value).isNull()
        assertThat(registry.profiles.value).isEmpty()
        assertThat(registry.beingRemoved()).containsExactly("p0")
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
        // The one that could not be read is still written as the current one, for a start that reads it.
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(third))
        assertThat(restarted.current.value?.id).isEqualTo("p1")
        assertThat(restarted.profiles.value.map { it.id }).containsExactly("p1")
        assertThat(restarted.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `a profile whose key is lost is still removed beside one that only waits`() = runTest {
        val dataStore = dataStore()
        dataStore.writeReady(session, otherSession)
        // The profiles are read in the order they were made: the first one's tokens can never be read again,
        // and the keystore stops answering before the second.
        var asked = 0
        val lostThenSilent = object : TokenCipher by FakeTokenCipher() {
            override fun decryptOrThrow(cipherText: String): String? =
                if (asked++ == 0) null else throw IOException("The keystore failed")
        }

        val registry = loaded(dataStore, lostThenSilent)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.beingRemoved()).containsExactly("p0")
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
        // What was written for the first profile did not unseat the second, which was the current one.
        assertThat(loaded(dataStore).state.value).isEqualTo(SessionState.SignedIn(otherSession))
    }

    private companion object {
        const val OtherServer = "https://other.example.com"
    }
}
