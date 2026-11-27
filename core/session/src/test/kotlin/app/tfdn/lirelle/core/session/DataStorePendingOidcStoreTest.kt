package app.tfdn.lirelle.core.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePendingOidcStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val request = PendingOidcRequest(
        serverUrl = "https://books.example.com",
        providerSlug = "authentik",
        state = "state-1",
        codeVerifier = "verifier-1",
        nonce = "nonce-1",
    )

    /** One begun in the browser through the server: the return address it accepted, and its first call's cookies. */
    private val browserRequest = request.copy(
        providerSlug = "openid",
        nonce = "",
        begunAtMillis = 2_000,
        redirectUri = "lirelle://oauth",
        cookies = "connect.sid=s%3Asession-1; auth_method=openid-mobile",
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun dataStore() =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "oidc.preferences_pb") }

    @Test
    fun `take returns null when nothing is pending`() = runBlocking {
        val store = DataStorePendingOidcStore(dataStore(), FakeTokenCipher())

        assertThat(store.takeWhen { true }).isNull()
    }

    @Test
    fun `take returns the saved request once`() = runBlocking {
        val store = DataStorePendingOidcStore(dataStore(), FakeTokenCipher())
        store.save(request)

        assertThat(store.takeWhen { true }).isEqualTo(request)
        assertThat(store.takeWhen { true }).isNull()
    }

    @Test
    fun `a request that is not the one meant stays, for the one that means it`() = runBlocking {
        val store = DataStorePendingOidcStore(dataStore(), FakeTokenCipher())
        store.save(request)

        assertThat(store.takeWhen { it.state == "another" }).isNull()

        assertThat(store.isPending()).isTrue()
        assertThat(store.takeWhen { it.state == "state-1" }).isEqualTo(request)
        assertThat(store.isPending()).isFalse()
    }

    @Test
    fun `the request can be looked at without using it up`() = runBlocking {
        val store = DataStorePendingOidcStore(dataStore(), FakeTokenCipher())
        assertThat(store.peek()).isNull()
        store.save(request.copy(begunAtMillis = 1_000))

        assertThat(store.peek()).isEqualTo(request.copy(begunAtMillis = 1_000))
        assertThat(store.isPending()).isTrue()
    }

    @Test
    fun `a request stored before its time was kept has none`() = runBlocking {
        val dataStore = dataStore()
        val old = """{"serverUrl":"https://books.example.com","providerSlug":"authentik","state":"state-1",""" +
            """"codeVerifier":"verifier-1","nonce":"nonce-1"}"""
        dataStore.edit { it[stringPreferencesKey("pending_oidc")] = FakeTokenCipher().encrypt(old) }

        val read = DataStorePendingOidcStore(dataStore, FakeTokenCipher()).peek()

        assertThat(read).isEqualTo(request)
        assertThat(read?.begunAtMillis).isNull()
    }

    @Test
    fun `a request stored before the return address and the cookies were kept has neither`() = runBlocking {
        val dataStore = dataStore()
        val old = """{"serverUrl":"https://books.example.com","providerSlug":"authentik","state":"state-1",""" +
            """"codeVerifier":"verifier-1","nonce":"nonce-1","begunAtMillis":1000}"""
        dataStore.edit { it[stringPreferencesKey("pending_oidc")] = FakeTokenCipher().encrypt(old) }

        val read = DataStorePendingOidcStore(dataStore, FakeTokenCipher()).peek()

        assertThat(read).isEqualTo(request.copy(begunAtMillis = 1_000))
        assertThat(read?.redirectUri).isNull()
        assertThat(read?.cookies).isNull()
    }

    @Test
    fun `the return address and the cookies survive a write and a read, not in clear text`() = runBlocking {
        val dataStore = dataStore()
        val store = DataStorePendingOidcStore(dataStore, FakeTokenCipher())
        store.save(browserRequest)

        assertThat(store.peek()).isEqualTo(browserRequest)
        assertThat(dataStore.data.first().asMap().values.joinToString()).doesNotContain("s%3Asession-1")
        assertThat(store.takeWhen { true }).isEqualTo(browserRequest)
    }

    @Test
    fun `a BookOrbit request is written as it was before the return address and the cookies were kept`() =
        runBlocking {
            val dataStore = dataStore()
            DataStorePendingOidcStore(dataStore, FakeTokenCipher()).save(request)

            val written = FakeTokenCipher().decrypt(dataStore.data.first()[stringPreferencesKey("pending_oidc")]!!)

            assertThat(written).doesNotContain("redirectUri")
            assertThat(written).doesNotContain("cookies")
        }

    @Test
    fun `an undecryptable request is not looked at, and goes when it is asked for`() = runBlocking {
        val dataStore = dataStore()
        DataStorePendingOidcStore(dataStore, FakeTokenCipher()).save(request)
        val store = DataStorePendingOidcStore(dataStore, FakeTokenCipher(broken = true))

        assertThat(store.peek()).isNull()
        assertThat(store.takeWhen { false }).isNull()

        assertThat(store.isPending()).isFalse()
    }

    @Test
    fun `tells whether a request is pending without using it up`() = runBlocking {
        val store = DataStorePendingOidcStore(dataStore(), FakeTokenCipher())
        assertThat(store.isPending()).isFalse()

        store.save(request)
        assertThat(store.isPending()).isTrue()
        assertThat(store.isPending()).isTrue()
        assertThat(store.takeWhen { true }).isEqualTo(request)

        assertThat(store.isPending()).isFalse()
    }

    @Test
    fun `the request survives a restart`() = runBlocking {
        val firstRun = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstRun) {
            File(folder.root, "oidc.preferences_pb")
        }
        DataStorePendingOidcStore(firstDataStore, FakeTokenCipher()).save(request)
        // Stopped and waited for, so the file can be opened again as after a process restart.
        firstRun.coroutineContext[Job]!!.cancelAndJoin()

        assertThat(DataStorePendingOidcStore(dataStore(), FakeTokenCipher()).takeWhen { true }).isEqualTo(request)
    }

    @Test
    fun `the code verifier is not stored in clear text`() = runBlocking {
        val dataStore = dataStore()
        DataStorePendingOidcStore(dataStore, FakeTokenCipher()).save(request)

        assertThat(dataStore.data.first().asMap().values.joinToString()).doesNotContain("verifier-1")
    }

    @Test
    fun `clear removes the pending request`() = runBlocking {
        val store = DataStorePendingOidcStore(dataStore(), FakeTokenCipher())
        store.save(request)

        store.clear()

        assertThat(store.takeWhen { true }).isNull()
    }

    @Test
    fun `an undecryptable request reads as nothing pending`() = runBlocking {
        val dataStore = dataStore()
        DataStorePendingOidcStore(dataStore, FakeTokenCipher()).save(request)

        assertThat(DataStorePendingOidcStore(dataStore, FakeTokenCipher(broken = true)).takeWhen { true }).isNull()
    }

    @Test
    fun `concurrent takes hand the request to exactly one caller`() = runBlocking(Dispatchers.Default) {
        val store = DataStorePendingOidcStore(dataStore(), FakeTokenCipher())

        repeat(TakeRounds) {
            store.save(request)

            val taken = List(ConcurrentTakes) { async { store.takeWhen { true } } }.awaitAll()

            assertThat(taken.filterNotNull()).containsExactly(request)
        }
    }

    @Test
    fun `a pending request does not print its secrets`() {
        val printed = request.toString()

        assertThat(printed).doesNotContain("state-1")
        assertThat(printed).doesNotContain("verifier-1")
        assertThat(printed).doesNotContain("nonce-1")
        assertThat(printed).contains("https://books.example.com")
        assertThat(printed).contains("authentik")
    }

    @Test
    fun `a pending request does not print its cookies, but says where it returns`() {
        val printed = browserRequest.toString()

        assertThat(printed).doesNotContain("session-1")
        assertThat(printed).doesNotContain("openid-mobile")
        assertThat(printed).contains("lirelle://oauth")
    }

    private companion object {
        const val TakeRounds = 50
        const val ConcurrentTakes = 16
    }
}
