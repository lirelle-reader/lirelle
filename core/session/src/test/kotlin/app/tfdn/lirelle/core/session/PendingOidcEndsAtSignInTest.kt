package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.session.Sample.Server
import app.tfdn.lirelle.core.session.Sample.otherSession
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An OIDC sign-in waiting for its redirect, kept in the registry's file, ends when somebody signs in: a browser
 * tab finished later signs nobody in. Nothing else the registry writes ends it.
 */
class PendingOidcEndsAtSignInTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cipher = FakeTokenCipher()
    private val request = PendingOidcRequest(Server, "provider", "state", "verifier", "nonce", begunAtMillis = 1)
    private var counter = 0

    private fun TestScope.dataStore() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") }

    private suspend fun registry(dataStore: DataStore<Preferences>) =
        DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }.also { it.load() }

    @Test
    fun `a sign-in that adds a profile ends the OIDC sign-in that waited`() = runTest {
        val dataStore = dataStore()
        val registry = registry(dataStore)
        val pending = DataStorePendingOidcStore(dataStore, cipher)
        registry.save(session)
        pending.save(request)

        registry.save(otherSession)

        assertThat(pending.isPending()).isFalse()
        assertThat(registry.profiles.value.map { it.session }).containsExactly(otherSession, session).inOrder()
    }

    @Test
    fun `a profile signing in again ends the OIDC sign-in that waited`() = runTest {
        val dataStore = dataStore()
        val registry = registry(dataStore)
        val pending = DataStorePendingOidcStore(dataStore, cipher)
        registry.save(session)
        pending.save(request)

        registry.save(session.copyCredential(sessionId = 77))

        assertThat(pending.isPending()).isFalse()
    }

    @Test
    fun `what is not a sign-in leaves the OIDC sign-in waiting`() = runTest {
        val dataStore = dataStore()
        val registry = registry(dataStore)
        val pending = DataStorePendingOidcStore(dataStore, cipher)
        registry.save(session)
        registry.save(otherSession)
        pending.save(request)

        registry.setServerUrl("https://other.example.net")
        registry.switchTo("p0")
        registry.updateTokens("p0", session.tokens.copy(accessToken = "renewed"), replacing = session.tokens)
        registry.remove("p1", SignOutReason.UserRequest)

        assertThat(pending.peek()).isEqualTo(request)
    }
}
