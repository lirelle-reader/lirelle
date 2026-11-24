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

/** What the first read decides is decided for good, and what it could not read is not taken for empty. */
class ProfileRegistryStartTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    private fun TestScope.dataStore() = GatedDataStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") },
    )

    /** A new registry over the same store, as after a process restart. Ids go on: p0, p1… */
    private suspend fun loaded(dataStore: DataStore<Preferences>, cipher: TokenCipher = FakeTokenCipher()) =
        DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }.also { it.load() }

    @Test
    fun `a profile found unreadable stays being removed when its tokens can be read again`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        // The key is taken for lost at this start: the profile's folders begin to be deleted.
        assertThat(loaded(dataStore, FakeTokenCipher(broken = true)).beingRemoved()).containsExactly("p0")

        // The process died meanwhile, and the tokens decrypt after all at the next start.
        val restarted = loaded(dataStore)

        assertThat(restarted.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(restarted.current.value).isNull()
        assertThat(restarted.profile("p0")).isNull()
        assertThat(restarted.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `an unreadable profile that was not current is written as being removed too`() = runTest {
        val dataStore = dataStore()
        dataStore.writeReady(session, otherSession)
        val first = loaded(dataStore)
        first.remove("p1", SignOutReason.UserRequest)
        first.forget("p1")
        assertThat(loaded(dataStore, FakeTokenCipher(broken = true)).beingRemoved()).containsExactly("p0")

        val restarted = loaded(dataStore)

        assertThat(restarted.profile("p0")).isNull()
        assertThat(restarted.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `a start that cannot write what it decided still signs out`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        dataStore.failWrites = true

        val registry = loaded(dataStore, FakeTokenCipher(broken = true))

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `a registry that could not be read does not say what it lists`() = runTest {
        val registry = loaded(UnreadableDataStore())

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(serverUrl = null, reason = null))
        assertThat(registry.listedIds()).isNull()
        assertThat(registry.beingRemoved()).isEmpty()
    }

    @Test
    fun `what a registry that could not be read lists stays unknown until the next start`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        dataStore.failNextRead = true
        val registry = loaded(dataStore)
        assertThat(registry.listedIds()).isNull()

        // The profile made here is known; those the file listed before are not.
        registry.save(otherSession)
        registry.setServerUrl("https://other.example.com")

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(registry.listedIds()).isNull()
        assertThat(loaded(dataStore).listedIds()).isNotNull()
    }

    @Test
    fun `a start that finds every profile readable writes nothing`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        val writes = dataStore.writes

        val restarted = loaded(dataStore)

        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(dataStore.writes).isEqualTo(writes)
    }
}
