package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A sign-in tells whoever signed in which session of the same account it took the place of on this device:
 * that session is still open on its server, and only the tokens the registry held can end it there.
 */
class ProfileRegistryReplacedTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    /** Ann signing in again: a new session of the same account, with tokens of its own. */
    private val again = session.copyCredential(
        sessionId = 77,
        tokens = session.tokens.copy(accessToken = "access-again", refreshToken = "refresh-again"),
    )

    private val other = session.copyCredential(
        sessionId = 43,
        tokens = session.tokens.copy(accessToken = "access-2", refreshToken = "refresh-2"),
    ).copy(
        user = UserProfile(id = "2", username = "other", name = "Bob Other", email = null),
    )

    private fun TestScope.dataStore() = GatedDataStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") },
    )

    private suspend fun loaded(dataStore: DataStore<Preferences>, cipher: TokenCipher = FakeTokenCipher()) =
        DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }.also { it.load() }

    @Test
    fun `a first sign-in of an account replaces nothing`() = runTest {
        val registry = loaded(dataStore())
        registry.save(other)

        assertThat(registry.save(session)).isNull()
    }

    @Test
    fun `signing in again to a ready profile's account gives the tokens it held`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        registry.save(other)

        val replaced = registry.save(again)

        assertThat(replaced).isEqualTo(ReplacedSession(session.credential, refused = false))
        assertThat(registry.current.value?.session).isEqualTo(again)
    }

    @Test
    fun `signing in again to a profile that waited for it says that its tokens were refused`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        registry.needSignIn("p0", session.tokens)

        assertThat(registry.save(again)).isEqualTo(ReplacedSession(session.credential, refused = true))
    }

    @Test
    fun `a profile whose tokens could not be read at this start gives none`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        val registry = loaded(dataStore, FakeTokenCipher(failingForNow = true))

        // Taken over, but what it held is not known: there is nothing to end.
        assertThat(registry.save(again)).isNull()
        assertThat(registry.current.value?.id).isEqualTo("p0")
    }
}
