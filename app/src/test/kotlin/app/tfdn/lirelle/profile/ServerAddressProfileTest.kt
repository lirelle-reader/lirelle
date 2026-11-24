package app.tfdn.lirelle.profile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.tfdn.lirelle.core.network.ServerAddress
import app.tfdn.lirelle.core.session.DataStoreProfileRegistry
import app.tfdn.lirelle.core.session.TokenCipher
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A profile is matched on its server's address as a string, which holds only because the address is made one
 * where it is typed. Kept here, not with the registry's tests: the registry's module does not see
 * [ServerAddress], which lives in the network's.
 */
class ServerAddressProfileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Nothing here is about the keystore: the tokens are written as they are. */
    private val noCipher = object : TokenCipher {
        override fun encrypt(plainText: String) = plainText
        override fun decrypt(cipherText: String) = cipherText
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun signedInOn(typed: String) = bookOrbitSession(
        serverUrl = (ServerAddress.parse(typed) as ServerAddress.Valid).url,
        sessionId = 1,
        tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX),
        user = UserProfile(id = "1", username = "reader", name = "Ann Reader", email = null),
    )

    @Test
    fun `one server typed with a slash, without, and in capitals is one profile`(): Unit = runBlocking {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(folder.root, "profiles.preferences_pb")
        }
        var made = 0
        val registry = DataStoreProfileRegistry(dataStore, noCipher) { "p${made++}" }
        registry.load()

        listOf("https://x", "https://x/", "HTTPS://X").forEach { registry.save(signedInOn(it)) }

        assertThat(registry.profiles.value.map { it.id }).containsExactly("p0")
        assertThat(registry.listedIds()).containsExactly("p0")
        assertThat(registry.current.value?.session?.serverUrl).isEqualTo("https://x")
    }
}
