package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The kind of server is remembered beside the address, so that the sign-in step knows which kind it signs in
 * to without asking the server again.
 */
class RememberedServerKindTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") }

    private suspend fun loaded(dataStore: DataStore<Preferences>) =
        DataStoreProfileRegistry(dataStore, FakeTokenCipher()) { "p${counter++}" }.also { it.load() }

    @Test
    fun `nothing is remembered on a new device`() = runTest {
        val registry = loaded(dataStore())

        assertThat(registry.serverKind.value).isNull()
    }

    @Test
    fun `the kind is remembered beside the address, and after a restart`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)

        registry.setServerUrl("http://homeserver:3000", ServerKind.BookOrbit)

        assertThat(registry.serverKind.value).isEqualTo(ServerKind.BookOrbit)
        assertThat(dataStore.data.first()[RegistryKeys.ServerKind]).isEqualTo("BookOrbit")
        val restarted = loaded(dataStore)
        assertThat(restarted.serverUrl.value).isEqualTo("http://homeserver:3000")
        assertThat(restarted.serverKind.value).isEqualTo(ServerKind.BookOrbit)
    }

    @Test
    fun `an address remembered by a version that knew BookOrbit alone is a BookOrbit server's`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[RegistryKeys.ServerUrl] = "https://books.example.com" }

        val registry = loaded(dataStore)

        assertThat(registry.serverUrl.value).isEqualTo("https://books.example.com")
        assertThat(registry.serverKind.value).isEqualTo(ServerKind.BookOrbit)
    }

    @Test
    fun `a kind this version does not know is none, and stays written until another server is remembered`() =
        runTest {
            val dataStore = dataStore()
            dataStore.writeReady(session)
            dataStore.edit { it[RegistryKeys.ServerKind] = "Later" }
            val registry = loaded(dataStore)

            assertThat(registry.serverUrl.value).isEqualTo(Sample.Server)
            assertThat(registry.serverKind.value).isNull()
            registry.updateUser("p0", session.user.copy(name = "Ann R."))
            assertThat(dataStore.data.first()[RegistryKeys.ServerKind]).isEqualTo("Later")

            registry.setServerUrl("https://other.example.com", ServerKind.BookOrbit)

            assertThat(dataStore.data.first()[RegistryKeys.ServerKind]).isEqualTo("BookOrbit")
        }

    @Test
    fun `a sign-in remembers the kind it signed in to, and so does signing out of the last profile`() = runTest {
        val dataStore = dataStore()
        dataStore.edit {
            it[RegistryKeys.ServerUrl] = "https://other.example.com"
            it[RegistryKeys.ServerKind] = "Later"
        }
        val registry = loaded(dataStore)

        registry.save(session, ServerKind.BookOrbit)

        assertThat(registry.serverUrl.value).isEqualTo(Sample.Server)
        assertThat(registry.serverKind.value).isEqualTo(ServerKind.BookOrbit)
        dataStore.edit { it[RegistryKeys.ServerKind] = "Later" }
        registry.clearCurrent(SignOutReason.UserRequest)
        assertThat(registry.serverKind.value).isEqualTo(ServerKind.BookOrbit)
        assertThat(dataStore.data.first()[RegistryKeys.ServerKind]).isEqualTo("BookOrbit")
    }
}
