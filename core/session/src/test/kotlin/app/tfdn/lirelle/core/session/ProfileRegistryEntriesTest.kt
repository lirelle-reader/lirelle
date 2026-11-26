package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.tfdn.lirelle.core.session.Sample.Server
import app.tfdn.lirelle.core.session.Sample.otherSession
import app.tfdn.lirelle.core.session.Sample.session
import app.tfdn.lirelle.core.session.Sample.tokens
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An entry of the registry that this version cannot understand — another kind of server, a field missing or
 * of another shape — is somebody's profile all the same: kept as written, its folders left alone.
 */
class ProfileRegistryEntriesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") }

    /** A new registry over the same store, as after a process restart. Ids go on: p0, p1… */
    private suspend fun loaded(dataStore: DataStore<Preferences>) =
        DataStoreProfileRegistry(dataStore, FakeTokenCipher()) { "p${counter++}" }.also { it.load() }

    /** Two profiles, p0 then p1; [currentIsFirst] says which one the app shows. */
    private suspend fun twoProfiles(dataStore: DataStore<Preferences>, currentIsFirst: Boolean = false) {
        dataStore.writeReady(session, otherSession, current = if (currentIsFirst) 0 else 1)
    }

    /** Rewrites the stored registry by hand; [old] must be there, and its first occurrence is p0's. */
    private suspend fun alter(dataStore: DataStore<Preferences>, old: String, new: String) {
        dataStore.edit {
            val raw = it[RegistryKeys.Profiles]!!
            assertThat(raw).contains(old)
            it[RegistryKeys.Profiles] = raw.replaceFirst(old, new)
        }
    }

    private suspend fun entries(dataStore: DataStore<Preferences>): List<JsonElement> =
        Json.parseToJsonElement(dataStore.data.first()[RegistryKeys.Profiles]!!).jsonObject["profiles"]!!.jsonArray

    private suspend fun entry(dataStore: DataStore<Preferences>, id: String): JsonObject =
        entries(dataStore).filterIsInstance<JsonObject>().single { it["id"].toString() == "\"$id\"" }

    private suspend fun assertLeftAlone(registry: DataStoreProfileRegistry, id: String) {
        assertThat(registry.profile(id)).isNull()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.listedIds()).contains(id)
    }

    @Test
    fun `a profile of a kind this version does not know is listed, and neither opened nor removed`() = runTest {
        val dataStore = dataStore()
        twoProfiles(dataStore)
        alter(dataStore, KindStored, KindOfLater)

        val registry = loaded(dataStore)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertLeftAlone(registry, "p0")
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
    }

    @Test
    fun `a current profile of an unknown kind is neither opened nor removed, and gives way to the next`() = runTest {
        val dataStore = dataStore()
        twoProfiles(dataStore, currentIsFirst = true)
        alter(dataStore, KindStored, KindOfLater)

        val registry = loaded(dataStore)

        // As for a current profile that cannot be read for now: nobody is signed out while one can be opened.
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertLeftAlone(registry, "p0")
    }

    @Test
    fun `an entry of an unknown kind stays in the file as it was, whatever is written after`() = runTest {
        val dataStore = dataStore()
        twoProfiles(dataStore)
        alter(dataStore, KindStored, KindOfLater)
        val asWritten = entry(dataStore, "p0")
        assertThat(asWritten.toString()).contains(KindOfLater)
        val registry = loaded(dataStore)

        registry.save(session.copy(serverUrl = "https://third.example.com"))
        registry.renew("p1", tokens.copy(accessToken = "access-2"))
        // Nothing this version does to a profile reaches one it cannot read.
        registry.renew("p0", tokens.copy(accessToken = "access-3"))
        registry.remove("p0", SignOutReason.UserRequest)
        registry.forget("p0")

        assertThat(entry(dataStore, "p0")).isEqualTo(asWritten)
        assertThat(entries(dataStore)).hasSize(3)
        val restarted = loaded(dataStore)
        // The account that signed in came beside p1, which this version can read, and p0 is still not a profile.
        assertThat(restarted.profile("p0")).isNull()
        assertThat(restarted.profile("p1")).isNotNull()
        assertThat(restarted.beingRemoved()).isEmpty()
        assertThat(restarted.listedIds()).containsExactly("p0", "p1", "p2")
    }

    @Test
    fun `a new profile never takes the id of an entry that could not be read`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        alter(dataStore, KindStored, KindOfLater)
        val registry = loaded(dataStore)
        counter = 0

        registry.save(otherSession)

        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
    }

    @Test
    fun `an entry with a field missing is left alone, and the others load`() = runTest {
        val dataStore = dataStore()
        twoProfiles(dataStore)
        alter(dataStore, "\"sessionId\":42,", "")
        val asWritten = entry(dataStore, "p0")

        val registry = loaded(dataStore)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertLeftAlone(registry, "p0")
        registry.renew("p1", tokens.copy(accessToken = "access-2"))
        assertThat(entry(dataStore, "p0")).isEqualTo(asWritten)
    }

    @Test
    fun `an entry with a field of another shape is left alone, and the others load`() = runTest {
        val dataStore = dataStore()
        twoProfiles(dataStore)
        alter(dataStore, "\"sessionId\":42,", "\"sessionId\":{\"high\":0,\"low\":42},")
        val asWritten = entry(dataStore, "p0")

        val registry = loaded(dataStore)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertLeftAlone(registry, "p0")
        registry.renew("p1", tokens.copy(accessToken = "access-2"))
        assertThat(entry(dataStore, "p0")).isEqualTo(asWritten)
    }

    @Test
    fun `an entry that names no profile is kept in the file, and the others load`() = runTest {
        val dataStore = dataStore()
        twoProfiles(dataStore)
        alter(dataStore, "\"profiles\":[", "\"profiles\":[7,{\"kind\":\"BookOrbit\"},")

        val registry = loaded(dataStore)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(registry.profile("p0")?.session).isEqualTo(session)
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
        assertThat(registry.beingRemoved()).isEmpty()
        registry.renew("p1", tokens.copy(accessToken = "access-2"))
        assertThat(entries(dataStore).map { it.toString() }).containsAtLeast("7", "{\"kind\":\"BookOrbit\"}")
        assertThat(entries(dataStore)).hasSize(4)
    }

    private companion object {
        const val KindStored = "\"kind\":\"BookOrbit\""

        /** What a later version, that knows another server and keeps more about a profile, would write. */
        const val KindOfLater = "\"kind\":\"Audiobookshelf\",\"library\":{\"pinned\":[3,5]}"
    }
}
