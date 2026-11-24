package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.tfdn.lirelle.core.session.Sample.Server
import app.tfdn.lirelle.core.session.Sample.otherSession
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The registry written by the version before the app named things by ids of its own: the account's id was a
 * number, and nothing said of which format a profile's data was. It is migrated, not started again: its account
 * ids read as text, and its profiles hold data of the old format until each is opened and started again.
 */
class ProfileRegistryFormatTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    private fun TestScope.dataStore() = GatedDataStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") },
    )

    /** A new registry over the same store, as after a process restart. Ids go on: p0, p1… */
    private suspend fun loaded(dataStore: DataStore<Preferences>) =
        DataStoreProfileRegistry(dataStore, FakeTokenCipher()) { "p${counter++}" }.also { it.load() }

    /**
     * An entry as the version before wrote it for [of]: the account's id as [accountId] — a JSON number then —
     * and no format of its data.
     */
    private fun entryOfBefore(id: String, of: Session, accountId: String = of.user.id): String {
        val tokens = """{"accessToken":"${of.tokens.accessToken}","accessTokenExpiresAt":"2026-11-13T08:15:00Z",""" +
            """"refreshToken":"${of.tokens.refreshToken}","refreshTokenExpiresAt":"2026-11-20T08:00:00Z"}"""
        val email = of.user.email?.let { ""","email":"$it"""" }.orEmpty()
        val user = """{"id":$accountId,"username":"${of.user.username}","name":"${of.user.name}"$email}"""
        return """{"id":"$id","kind":"BookOrbit","session":{"serverUrl":"${of.serverUrl}",""" +
            """"sessionId":${(of.credential as Credential.SessionTokens).sessionId},"user":$user,""" +
            """"encryptedTokens":${JsonPrimitive(FakeTokenCipher().encrypt(tokens))}}}"""
    }

    private suspend fun writeBefore(dataStore: DataStore<Preferences>, current: String, vararg entries: String) {
        dataStore.edit {
            it[RegistryKeys.Profiles] = """{"profiles":[${entries.joinToString(",")}],"current":"$current"}"""
            it[RegistryKeys.ServerUrl] = Server
        }
    }

    /** Ann (account 1) as "ann", current, and Bob (account 2) as "bob", as the version before wrote them. */
    private suspend fun annAndBobOfBefore(dataStore: DataStore<Preferences>) =
        writeBefore(dataStore, "ann", entryOfBefore("ann", session), entryOfBefore("bob", otherSession))

    private suspend fun entry(dataStore: DataStore<Preferences>, id: String): JsonObject =
        Json.parseToJsonElement(dataStore.data.first()[RegistryKeys.Profiles]!!).jsonObject["profiles"]!!.jsonArray
            .filterIsInstance<JsonObject>().single { it["id"] == JsonPrimitive(id) }

    private fun JsonObject.accountId() = this["session"]!!.jsonObject["user"]!!.jsonObject["id"]

    @Test
    fun `the account ids of the version before read as their text, and its profiles' data as of the old format`() =
        runTest {
            val dataStore = dataStore()
            annAndBobOfBefore(dataStore)

            val registry = loaded(dataStore)

            assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
            assertThat(registry.current.value?.session?.user?.id).isEqualTo("1")
            assertThat(registry.profile("bob")?.session).isEqualTo(otherSession)
            assertThat(registry.profiles.value.map { it.id to it.dataFormat })
                .containsExactly("ann" to OldDataFormat, "bob" to OldDataFormat).inOrder()
        }

    @Test
    fun `the account id is written back as text, and the format stays the old one until the data starts again`() =
        runTest {
            val dataStore = dataStore()
            annAndBobOfBefore(dataStore)
            assertThat(entry(dataStore, "ann").accountId()).isEqualTo(JsonPrimitive(1))
            val registry = loaded(dataStore)

            registry.renew("ann", session.tokens.copy(accessToken = "access-renewed"))
            registry.switchTo("bob")

            assertThat(entry(dataStore, "ann").accountId()).isEqualTo(JsonPrimitive("1"))
            assertThat(entry(dataStore, "bob").accountId()).isEqualTo(JsonPrimitive("2"))
            val restarted = loaded(dataStore)
            assertThat(restarted.profile("ann")?.session?.tokens?.accessToken).isEqualTo("access-renewed")
            assertThat(restarted.profiles.value.map { it.dataFormat }).containsExactly(OldDataFormat, OldDataFormat)
        }

    @Test
    fun `the account signing in again finds its profile of the version before`() = runTest {
        val dataStore = dataStore()
        annAndBobOfBefore(dataStore)
        val registry = loaded(dataStore)
        val again = session.copyCredential(sessionId = 77, tokens = session.tokens.copy(refreshToken = "refresh-again"))

        val replaced = registry.save(again)

        assertThat(replaced).isNotNull()
        assertThat(registry.profiles.value.map { it.id }).containsExactly("ann", "bob").inOrder()
        assertThat(registry.current.value?.session).isEqualTo(again)
        // Signing in again is not starting the data again: that is done when the profile is opened.
        assertThat(registry.current.value?.dataFormat).isEqualTo(OldDataFormat)
    }

    @Test
    fun `an account id that is neither a number nor text leaves its entry unread, as it was written`() = runTest {
        val dataStore = dataStore()
        writeBefore(
            dataStore,
            "ann",
            entryOfBefore("ann", session),
            entryOfBefore("odd", otherSession, accountId = "true"),
            entryOfBefore("half", otherSession.copy(serverUrl = "https://half.example.com"), accountId = "1.5"),
        )
        val odd = entry(dataStore, "odd")
        val half = entry(dataStore, "half")

        val registry = loaded(dataStore)
        registry.renew("ann", session.tokens.copy(accessToken = "access-renewed"))

        assertThat(registry.profiles.value.map { it.id }).containsExactly("ann")
        assertThat(registry.listedIds()).containsExactly("ann", "odd", "half")
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(entry(dataStore, "odd")).isEqualTo(odd)
        assertThat(entry(dataStore, "half")).isEqualTo(half)
    }

    @Test
    fun `an account id the server gives as text is kept as it is`() = runTest {
        val dataStore = dataStore()
        val komga = session.copy(user = session.user.copy(id = "0B5Y2S1Z"))
        loaded(dataStore).save(komga)

        assertThat(entry(dataStore, "p0").accountId()).isEqualTo(JsonPrimitive("0B5Y2S1Z"))
        assertThat(loaded(dataStore).current.value?.session).isEqualTo(komga)
    }

    @Test
    fun `a profile signed in from now on holds data of the current format`() = runTest {
        val dataStore = dataStore()

        loaded(dataStore).save(session)

        assertThat(entry(dataStore, "p0")["dataFormat"]).isEqualTo(JsonPrimitive(CurrentDataFormat))
        assertThat(loaded(dataStore).current.value?.dataFormat).isEqualTo(CurrentDataFormat)
    }

    @Test
    fun `a profile whose data started again is of the current format, and nothing else of it changes`() = runTest {
        val dataStore = dataStore()
        annAndBobOfBefore(dataStore)
        val registry = loaded(dataStore)

        registry.dataStartedAgain("bob")

        assertThat(registry.profile("bob")?.dataFormat).isEqualTo(CurrentDataFormat)
        val restarted = loaded(dataStore)
        assertThat(restarted.profile("bob")?.dataFormat).isEqualTo(CurrentDataFormat)
        assertThat(restarted.profile("bob")?.session).isEqualTo(otherSession)
        assertThat(restarted.profile("ann")?.dataFormat).isEqualTo(OldDataFormat)
        assertThat(restarted.current.value?.id).isEqualTo("ann")
    }

    @Test
    fun `a profile withheld is neither current nor listed at this start, and stays as written for the next`() =
        runTest {
            val dataStore = dataStore()
            annAndBobOfBefore(dataStore)
            val registry = loaded(dataStore)
            val writes = dataStore.writes

            registry.withhold("ann")

            assertThat(registry.current.value?.id).isEqualTo("bob")
            assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
            assertThat(registry.profiles.value.map { it.id }).containsExactly("bob")
            assertThat(registry.profile("ann")).isNull()
            assertThat(registry.listedIds()).containsExactly("ann", "bob")
            assertThat(registry.beingRemoved()).isEmpty()
            assertThat(dataStore.writes).isEqualTo(writes)
            val restarted = loaded(dataStore)
            assertThat(restarted.current.value?.id).isEqualTo("ann")
            assertThat(restarted.current.value?.dataFormat).isEqualTo(OldDataFormat)
        }

    @Test
    fun `the last profile withheld leaves nobody signed in`() = runTest {
        val dataStore = dataStore()
        writeBefore(dataStore, "ann", entryOfBefore("ann", session))
        val registry = loaded(dataStore)

        registry.withhold("ann")

        assertThat(registry.current.value).isNull()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
    }

    @Test
    fun `the account of a profile withheld signing in takes it over, with the format of its data`() = runTest {
        val dataStore = dataStore()
        annAndBobOfBefore(dataStore)
        val registry = loaded(dataStore)
        registry.withhold("ann")

        registry.save(session)

        assertThat(registry.current.value?.id).isEqualTo("ann")
        assertThat(registry.current.value?.dataFormat).isEqualTo(OldDataFormat)
        assertThat(registry.listedIds()).containsExactly("ann", "bob")
    }
}
