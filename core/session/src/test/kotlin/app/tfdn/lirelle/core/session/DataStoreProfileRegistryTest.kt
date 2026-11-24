package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreProfileRegistryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    private val tokens = Tokens(
        accessToken = "access-1",
        accessTokenExpiresAt = Instant.parse("2026-11-13T08:15:00Z"),
        refreshToken = "refresh-1",
        refreshTokenExpiresAt = Instant.parse("2026-11-20T08:00:00Z"),
    )
    private val renewed = tokens.copy(accessToken = "access-2", refreshToken = "refresh-2")
    private val session = Session(
        serverUrl = Server,
        credential = Credential.SessionTokens(sessionId = 42, tokens),
        user = UserProfile(id = "1", username = "reader", name = "Ann Reader", email = "ann@example.com"),
    )
    private val otherSession = session.copyCredential(sessionId = 43).copy(
        user = UserProfile(id = "2", username = "other", name = "Bob Other", email = null),
    )

    private val file by lazy { File(folder.root, "profiles.preferences_pb") }

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { file }

    /** A new registry over the same store, as after a process restart. Ids go on: p0, p1… */
    private fun registry(dataStore: DataStore<Preferences>, cipher: TokenCipher = FakeTokenCipher()) =
        DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }

    private suspend fun loaded(dataStore: DataStore<Preferences>, cipher: TokenCipher = FakeTokenCipher()) =
        registry(dataStore, cipher).also { it.load() }

    @Test
    fun `is loading until loaded, then signed out with no profile when nothing is stored`() = runTest {
        val registry = registry(dataStore())

        assertThat(registry.state.value).isEqualTo(SessionState.Loading)
        registry.load()

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(serverUrl = null, reason = null))
        assertThat(registry.current.value).isNull()
        assertThat(registry.listedIds()).isEmpty()
    }

    @Test
    fun `save makes a profile, makes it current, and both survive a restart`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)

        registry.save(session)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(registry.current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, session))
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(restarted.current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, session))
    }

    @Test
    fun `save of the same account keeps the profile and replaces its session`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        val again = session.copyCredential(sessionId = 77, tokens = renewed)

        registry.save(again)

        assertThat(registry.current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, again))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(again))
        assertThat(registry.listedIds()).containsExactly("p0")
    }

    @Test
    fun `save of another account makes its profile current, and the one before is kept`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(session)

        registry.save(otherSession)

        assertThat(registry.current.value).isEqualTo(Profile("p1", ServerKind.BookOrbit, otherSession))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        // The account that signs in comes beside the one that was there.
        assertThat(registry.profile("p0")?.session).isEqualTo(session)
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(restarted.profile("p0")?.session).isEqualTo(session)
        assertThat(restarted.beingRemoved()).isEmpty()
    }

    @Test
    fun `save of an account that has its profile leaves the other profiles as they are`() = runTest {
        val dataStore = dataStore()
        dataStore.writeReady(session, otherSession)
        val registry = loaded(dataStore)
        val again = otherSession.copyCredential(sessionId = 77, tokens = renewed)

        registry.save(again)

        assertThat(registry.current.value).isEqualTo(Profile("p1", ServerKind.BookOrbit, again))
        assertThat(registry.profile("p0")?.session).isEqualTo(session)
        assertThat(registry.beingRemoved()).isEmpty()
    }

    @Test
    fun `the same user id on another server is another profile`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)

        registry.save(session.copy(serverUrl = "https://other.example.com"))

        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertThat(registry.profile("p0")?.session).isEqualTo(session)
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
    }

    @Test
    fun `a new profile never takes an id that is still listed`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        counter = 0

        registry.save(otherSession)

        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
    }

    @Test
    fun `updateTokens on the current profile publishes them and persists them`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(session)

        val written = registry.renew("p0", renewed)

        val expected = session.copyCredential(tokens = renewed)
        assertThat(written).isEqualTo(Profile("p0", ServerKind.BookOrbit, expected))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(expected))
        assertThat(registry.current.value?.session).isEqualTo(expected)
        assertThat(loaded(dataStore).state.value).isEqualTo(SessionState.SignedIn(expected))
    }

    @Test
    fun `updateTokens on a profile that is not current leaves the session alone`() = runTest {
        val dataStore = dataStore()
        dataStore.writeReady(session, otherSession)
        val registry = loaded(dataStore)

        val written = registry.renew("p0", renewed)

        assertThat(written?.session).isEqualTo(session.copyCredential(tokens = renewed))
        assertThat(registry.profile("p0")?.session).isEqualTo(session.copyCredential(tokens = renewed))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(registry.current.value?.session).isEqualTo(otherSession)
        assertThat(loaded(dataStore).profile("p0")?.session).isEqualTo(session.copyCredential(tokens = renewed))
    }

    @Test
    fun `updateTokens on an unknown profile does nothing`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)

        val written = registry.renew("nobody", renewed)

        assertThat(written).isNull()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(registry.listedIds()).containsExactly("p0")
    }

    @Test
    fun `a download permission that was withdrawn is still withdrawn after a restart`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(session)
        assertThat(loaded(dataStore).current.value?.session?.user?.canDownload).isTrue()

        registry.updateUser("p0", session.user.copy(canDownload = false))

        // A stored account without the field reads as allowed: the field itself must be written.
        assertThat(loaded(dataStore).current.value?.session?.user?.canDownload).isFalse()
    }

    @Test
    fun `an account signed in without the permission to download has none after a restart`() = runTest {
        val dataStore = dataStore()
        val refused = session.copy(user = session.user.copy(canDownload = false))

        loaded(dataStore).save(refused)

        assertThat(loaded(dataStore).state.value).isEqualTo(SessionState.SignedIn(refused))
    }

    @Test
    fun `updateUser replaces the account and ignores another one`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        val changed = session.user.copy(name = "Ann B. Reader", canDownload = false)

        val written = registry.updateUser("p0", changed)
        // An answer about another account says nothing about this one.
        val ignored = registry.updateUser("p0", changed.copy(id = "2", name = "Somebody Else"))

        assertThat(written?.session).isEqualTo(session.copy(user = changed))
        assertThat(ignored).isNull()
        assertThat(registry.updateUser("p0", changed)).isNull()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session.copy(user = changed)))
    }

    @Test
    fun `clear signs out, and leaves the profile as being removed`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(session)

        registry.clearCurrent(SignOutReason.UserRequest)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        assertThat(registry.current.value).isNull()
        assertThat(registry.beingRemoved()).containsExactly("p0")
        assertThat(registry.profile("p0")).isNull()
        assertThat(registry.listedIds()).containsExactly("p0")
        // The reason is of this process only; the removal is still to be finished after a restart.
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(restarted.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `clear without a session does nothing`() = runTest {
        val dataStore = GatedDataStore(dataStore())
        val registry = loaded(dataStore)
        registry.setServerUrl(Server)
        val writes = dataStore.writes

        registry.clearCurrent(SignOutReason.UserRequest)

        // Neither a reason shown on the sign-in screen for a sign-out that never was, nor a write.
        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(dataStore.writes).isEqualTo(writes)
    }

    @Test
    fun `a second clear keeps the first reason`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        registry.clearCurrent(SignOutReason.UserRequest)

        // A renewal that was in flight when the user signed out reports its 401 afterwards.
        registry.clearCurrent(SignOutReason.UserRequest)
        registry.remove("p0", SignOutReason.UserRequest)
        registry.renew("p0", renewed)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        assertThat(registry.profile("p0")).isNull()
    }

    @Test
    fun `removing a profile that is not current leaves the session alone`() = runTest {
        val dataStore = dataStore()
        dataStore.writeReady(session, otherSession)
        val registry = loaded(dataStore)

        registry.remove("p0", SignOutReason.UserRequest)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertThat(registry.beingRemoved()).containsExactly("p0")
        assertThat(registry.profile("p0")).isNull()
    }

    @Test
    fun `a profile being removed is never reused, the same account gets a new id`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        registry.clearCurrent(SignOutReason.UserRequest)

        registry.save(session)

        assertThat(registry.current.value).isEqualTo(Profile("p1", ServerKind.BookOrbit, session))
        assertThat(registry.beingRemoved()).containsExactly("p0")
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
    }

    @Test
    fun `forget drops a profile being removed, and only such a profile`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(session)
        registry.clearCurrent(SignOutReason.UserRequest)
        registry.save(session)

        registry.forget("p0")
        registry.forget("p1")

        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.listedIds()).containsExactly("p1")
        assertThat(loaded(dataStore).listedIds()).containsExactly("p1")
    }

    @Test
    fun `setServerUrl is remembered while signed out`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)

        registry.setServerUrl("http://homeserver:3000")

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut("http://homeserver:3000", reason = null))
        assertThat(loaded(dataStore).state.value)
            .isEqualTo(SessionState.SignedOut("http://homeserver:3000", reason = null))
    }

    @Test
    fun `the remembered address is published, before anybody signs in and after a restart`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        assertThat(registry.serverUrl.value).isNull()

        registry.setServerUrl("http://homeserver:3000")

        assertThat(registry.serverUrl.value).isEqualTo("http://homeserver:3000")
        assertThat(loaded(dataStore).serverUrl.value).isEqualTo("http://homeserver:3000")
    }

    /** The address typed to add a profile: device data, which no profile owns. */
    @Test
    fun `setServerUrl is remembered while signed in, and changes nothing of the profiles`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(session)
        assertThat(registry.serverUrl.value).isEqualTo(Server)
        val profiles = registry.profiles.value

        registry.setServerUrl("https://other.example.com")

        assertThat(registry.serverUrl.value).isEqualTo("https://other.example.com")
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(registry.profiles.value).isEqualTo(profiles)
        val restarted = loaded(dataStore)
        assertThat(restarted.serverUrl.value).isEqualTo("https://other.example.com")
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(session))
    }

    @Test
    fun `signing out of the last profile remembers its server, whatever was typed meanwhile`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(session)
        registry.setServerUrl("https://other.example.com")

        registry.clearCurrent(SignOutReason.UserRequest)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        assertThat(registry.serverUrl.value).isEqualTo(Server)
        assertThat(loaded(dataStore).state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
    }

    @Test
    fun `setServerUrl with the address already remembered writes nothing`() = runTest {
        val dataStore = GatedDataStore(dataStore())
        val registry = loaded(dataStore)
        registry.setServerUrl("http://homeserver:3000")
        val writes = dataStore.writes

        registry.setServerUrl("http://homeserver:3000")

        assertThat(dataStore.writes).isEqualTo(writes)
        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut("http://homeserver:3000", reason = null))
    }

    @Test
    fun `setServerUrl with the address just signed out of takes the reason away`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        registry.clearCurrent(SignOutReason.UserRequest)

        registry.setServerUrl(Server)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
    }

    @Test
    fun `the stored file never holds a token in clear`() = runTest {
        val registry = loaded(dataStore())
        registry.save(session)
        registry.renew("p0", renewed)

        val stored = file.readBytes().toString(Charsets.ISO_8859_1)

        assertThat(stored).contains("books.example.com")
        listOf("access-1", "refresh-1", "access-2", "refresh-2").forEach { assertThat(stored).doesNotContain(it) }
    }

    @Test
    fun `a profile does not print its tokens`() {
        val printed = Profile("p0", ServerKind.BookOrbit, session).toString()

        assertThat(printed).contains("books.example.com")
        assertThat(printed).doesNotContain("access-1")
        assertThat(printed).doesNotContain("refresh-1")
    }

    @Test
    fun `unreadable stored profiles load as signed out with no profile`() = runTest {
        val dataStore = dataStore()
        dataStore.edit {
            it[ProfilesKey] = "{not json"
            it[ServerUrlKey] = Server
        }

        val registry = loaded(dataStore)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.current.value).isNull()
        assertThat(registry.listedIds()).isEmpty()
        // And the registry works again from there.
        registry.save(session)
        assertThat(loaded(dataStore).state.value).isEqualTo(SessionState.SignedIn(session))
    }

    @Test
    fun `a profile whose tokens cannot be decrypted is signed out and left to be removed`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)

        val registry = loaded(dataStore, FakeTokenCipher(broken = true))

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.current.value).isNull()
        assertThat(registry.profile("p0")).isNull()
        assertThat(registry.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `a current profile that is not ready is not opened`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(session)
        // What the migration leaves when it is interrupted: the profile is listed, still being moved in.
        dataStore.edit {
            val raw = it[ProfilesKey]!!
            assertThat(raw).contains(StoredId)
            it[ProfilesKey] = raw.replace(StoredId, "$StoredId,\"state\":\"moving\"")
        }

        val registry = loaded(dataStore)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.current.value).isNull()
        assertThat(registry.profile("p0")).isNull()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.listedIds()).containsExactly("p0")
    }

    private companion object {
        const val Server = "https://books.example.com"
        const val StoredId = "\"id\":\"p0\""
        val ProfilesKey = stringPreferencesKey("profiles")
        val ServerUrlKey = stringPreferencesKey("server_url")
    }
}
