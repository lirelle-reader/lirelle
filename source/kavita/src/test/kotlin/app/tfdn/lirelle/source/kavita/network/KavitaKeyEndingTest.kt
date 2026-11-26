package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.source.kavita.testing.KeyJson
import app.tfdn.lirelle.source.kavita.testing.MemoryStore
import app.tfdn.lirelle.source.kavita.testing.TheKey
import app.tfdn.lirelle.source.kavita.testing.TheKeyId
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.enqueueDone
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.enqueueRefusal
import app.tfdn.lirelle.source.kavita.testing.enqueueText
import app.tfdn.lirelle.source.kavita.testing.kavitaSession
import app.tfdn.lirelle.source.kavita.testing.kavitaSignIn
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val TimeoutMillis = 5_000L

/** The key the app made for the profile the account had before it was added again. */
private val OldKey = Credential.ApiKey("0ld-k3y-0f-th3-d3v1c3", "17", ownedByApp = true)

/** A key the user pasted: theirs, which the app only forgets. */
private val UsersKey = Credential.ApiKey("p45t3d-k3y-0f-th3-us3r", "9", ownedByApp = false)

/**
 * A Kavita key lasts until it is deleted: whenever the app lets go of one it made, it deletes it on the server —
 * signing out of the current profile (after it is removed), the key of an account added again (unless the server
 * had refused it), and the key of a profile removed while it slept (no profile is open: the key is handed over).
 * At best: a server that does not answer is given up soon, and the profile is gone all the same. A key the user
 * pasted is theirs: it is forgotten, and nothing is ever sent for it.
 */
class KavitaKeyEndingTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private val silence = CountDownLatch(1)
    private val told = mutableListOf<String>()
    private lateinit var url: String
    private lateinit var signIn: KavitaSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = kavitaSignIn(store, log = NetworkLog { told += it })
    }

    @After
    fun tearDown() {
        silence.countDown()
        server.close()
    }

    /** The next request, waited for a while only: one that never comes fails the test instead of hanging it. */
    private fun nextRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(TimeoutMillis, TimeUnit.MILLISECONDS)) { "Nothing was sent" }

    /** This request deletes [key], proven by [with]. */
    private fun RecordedRequest.assertDeletes(key: Credential.ApiKey, with: String = key.key) {
        assertThat(method).isEqualTo("DELETE")
        assertThat(pathText()).isEqualTo("/api/Account/auth-key")
        assertThat(url.query).isEqualTo("authKeyId=${key.keyId}")
        assertThat(headers["x-api-key"]).isEqualTo(with)
        assertThat(headers["Authorization"]).isNull()
        assertThat(headers["Cookie"]).isNull()
    }

    /** A server that takes the request and never answers. */
    private fun silentServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                silence.await(TimeoutMillis * 2, TimeUnit.MILLISECONDS)
                return MockResponse(code = 200)
            }
        }
    }

    // --- signing out of the current profile

    @Test
    fun `signing out removes the current Kavita profile, then deletes the key the app made, with the key itself`() =
        runBlocking<Unit> {
            store.current.value = Profile("p0", ServerKind.Kavita, kavitaSession(url))
            var currentSeenByServer: Profile? = store.current.value
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    currentSeenByServer = store.current.value
                    return MockResponse(code = 200)
                }
            }

            signIn.signOut("p0")

            assertThat(store.cleared).containsExactly("p0")
            assertThat(currentSeenByServer).isNull()
            nextRequest().assertDeletes(Credential.ApiKey(TheKey, TheKeyId))
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `signing out of a profile that holds the user's own key removes it, and sends nothing`() = runBlocking {
        store.current.value = Profile("p0", ServerKind.Kavita, kavitaSession(url, UsersKey))

        signIn.signOut("p0")

        assertThat(store.cleared).containsExactly("p0")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a sign-out whose server does not answer still removes the profile, and gives up soon`() = runBlocking {
        store.current.value = Profile("p0", ServerKind.Kavita, kavitaSession(url))
        silentServer()

        withTimeout(TimeoutMillis) { signIn.signOut("p0") }

        assertThat(store.cleared).containsExactly("p0")
        assertThat(server.requestCount).isEqualTo(1)
    }

    /** The profile switch tears the screen down as soon as the profile is gone: its key must not stay valid. */
    @Test
    fun `a sign-out whose caller is cancelled as the profile is removed still deletes the key`() = runBlocking {
        store.current.value = Profile("p0", ServerKind.Kavita, kavitaSession(url))
        server.enqueueDone()
        val signOut = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { signIn.signOut("p0") }
        store.afterClear = {
            signOut.cancel()
            // The registry's write coming back to a caller cancelled meanwhile.
            yield()
        }

        signOut.start()
        withTimeout(TimeoutMillis) { signOut.join() }

        assertThat(store.cleared).containsExactly("p0")
        nextRequest().assertDeletes(Credential.ApiKey(TheKey, TheKeyId))
    }

    @Test
    fun `a sign-out of a profile that is not current, or of another kind, sends nothing`() = runBlocking {
        store.current.value = Profile("p1", ServerKind.Kavita, kavitaSession(url))
        signIn.signOut("p0")
        store.current.value = Profile("p0", ServerKind.Komga, kavitaSession(url))
        signIn.signOut("p0")

        assertThat(store.cleared).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // --- an account added again

    private fun enqueuePasswordSignIn() {
        server.enqueueJson(annJson(withTokens = true))
        server.enqueueJson(KeyJson)
    }

    @Test
    fun `an account added again deletes the key the app had made, with the new one, once it is saved`() =
        runBlocking {
            store.replaced = ReplacedSession(OldKey, refused = false)
            enqueuePasswordSignIn()
            server.enqueueDone()

            signIn.signInWithPassword(url, "ann", "s3cr3t")

            assertThat(store.saved.single().first.credential).isEqualTo(Credential.ApiKey(TheKey, TheKeyId))
            repeat(2) { nextRequest() }
            nextRequest().assertDeletes(OldKey, with = TheKey)
            assertThat(server.requestCount).isEqualTo(3)
        }

    @Test
    fun `an account added again through its provider deletes the key the app had made too`() = runBlocking {
        store.replaced = ReplacedSession(OldKey, refused = false)
        server.enqueueJson(annJson())
        server.enqueueJson(KeyJson)
        server.enqueueDone()

        signIn.completeWithSession(url, ".AspNetCore.Cookies=c00k1e")

        repeat(2) { nextRequest() }
        nextRequest().assertDeletes(OldKey, with = TheKey)
    }

    @Test
    fun `an account added again with a pasted key deletes the key the app had made, with the pasted one`() =
        runBlocking {
            store.replaced = ReplacedSession(OldKey, refused = false)
            server.enqueueJson(annJson())
            server.enqueueJson("""[{"id":9,"key":"${UsersKey.key}","name":"Mine","provider":0}]""")
            server.enqueueDone()

            signIn.signInWithKey(url, UsersKey.key)

            assertThat(store.saved.single().first.credential).isEqualTo(UsersKey)
            repeat(2) { nextRequest() }
            nextRequest().assertDeletes(OldKey, with = UsersKey.key)
            assertThat(server.requestCount).isEqualTo(3)
        }

    @Test
    fun `the user's own key of an account added again is never deleted`() = runBlocking {
        store.replaced = ReplacedSession(UsersKey, refused = false)
        enqueuePasswordSignIn()

        signIn.signInWithPassword(url, "ann", "s3cr3t")

        assertThat(store.saved).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    /** The server refused that key: it is deleted already, or nothing can be done with it. */
    @Test
    fun `the key of an account added again is not sent when the server had refused it`() = runBlocking {
        store.replaced = ReplacedSession(OldKey, refused = true)
        enqueuePasswordSignIn()

        signIn.signInWithPassword(url, "ann", "s3cr3t")

        assertThat(store.saved).hasSize(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    /** Kavita lists every key in clear: the user may paste the very key the app made for this profile. */
    @Test
    fun `pasting the key the app had made keeps it, whether the server names it or not`() = runBlocking {
        store.replaced = ReplacedSession(OldKey, refused = false)
        server.enqueueJson(annJson())
        server.enqueueJson("""[{"id":17,"key":"${OldKey.key}","name":"Lirelle","provider":0}]""")
        signIn.signInWithKey(url, OldKey.key)
        server.enqueueJson(annJson())
        server.enqueueRefusal()
        signIn.signInWithKey(url, OldKey.key)

        assertThat(store.saved.map { it.first.credential }).containsExactly(
            OldKey.copy(ownedByApp = false),
            Credential.ApiKey(OldKey.key, "pasted", ownedByApp = false),
        ).inOrder()
        assertThat(server.requestCount).isEqualTo(4)
    }

    // --- a profile removed while it slept

    @Test
    fun `the key the app made for a profile removed while it slept is deleted with itself, and nothing else`() =
        runBlocking {
            server.enqueueDone()

            signIn.endCredential(url, OldKey)

            nextRequest().assertDeletes(OldKey)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(store.cleared).isEmpty()
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `the user's own key of a profile removed while it slept is only forgotten`() = runBlocking {
        signIn.endCredential(url, UsersKey)

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a credential that is no key sends nothing`() = runBlocking {
        signIn.endCredential(url, Credential.SessionTokens(1, Tokens("a", Instant.MAX, "r", Instant.MAX)))

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a server that does not answer is given up soon`() = runBlocking {
        silentServer()

        withTimeout(TimeoutMillis) { signIn.endCredential(url, OldKey) }

        assertThat(server.requestCount).isEqualTo(1)
    }

    // --- what is told of a key left valid

    @Test
    fun `a refused deletion changes nothing for the app, and is told by its status and path only`() = runBlocking {
        server.enqueueText("System.Exception: no such key\n   at API.Controllers.Account()", code = 400)

        signIn.endCredential(url, OldKey)

        assertThat(server.requestCount).isEqualTo(1)
        val line = told.single()
        assertThat(line).contains("400")
        assertThat(line).contains("/api/Account/auth-key")
        assertThat(line).doesNotContain(OldKey.key)
        assertThat(line).doesNotContain("System.Exception")
        assertThat(line).doesNotContain(server.hostName)
    }

    @Test
    fun `a deletion that never got its answer is told, never with the key or the server`() = runBlocking {
        silentServer()

        withTimeout(TimeoutMillis) { signIn.endCredential(url, OldKey) }

        val line = told.single()
        assertThat(line).doesNotContain(OldKey.key)
        assertThat(line).doesNotContain(server.hostName)
        assertThat(line).doesNotContain(server.port.toString())
    }

    @Test
    fun `a deleted key tells nothing`() = runBlocking {
        server.enqueueDone()

        signIn.endCredential(url, OldKey)

        assertThat(told).isEmpty()
    }
}
