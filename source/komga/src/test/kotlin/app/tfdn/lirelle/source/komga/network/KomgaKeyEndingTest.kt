package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.network.formatDeviceLabel
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.source.komga.testing.AnnJson
import app.tfdn.lirelle.source.komga.testing.KeyJson
import app.tfdn.lirelle.source.komga.testing.MemoryStore
import app.tfdn.lirelle.source.komga.testing.TheKey
import app.tfdn.lirelle.source.komga.testing.TheKeyId
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.enqueueNoContent
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pathText
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val TimeoutMillis = 5_000L

/** The key of the profile the account had before it was added again. */
private val OldKey = Credential.ApiKey("0ld-k3y", "0LD1D")

/**
 * A Komga key lasts until it is revoked: whenever the app lets go of one, it revokes it on the server with the key
 * itself — signing out of the current profile (after it is removed), the key of an account added again (unless the
 * server had refused it), and the key of a profile removed while it slept (no profile is open: the key is handed
 * over). At best: a server that does not answer is given up soon, and the profile is gone all the same.
 */
class KomgaKeyEndingTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private val silence = CountDownLatch(1)
    private lateinit var url: String
    private lateinit var signIn: KomgaSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = KomgaSignIn(
            store = store,
            api = KomgaApis(komgaSignInClient(OkHttpClient()), KomgaJson, KomgaPublicApi::class.java),
            errors = KomgaErrors(),
            deviceLabel = DeviceLabelProvider { formatDeviceLabel("Pixel 9") },
            revocation = KomgaRevocation(OkHttpClient(), KomgaJson, timeout = Duration.ofSeconds(1)),
            now = { Instant.parse("2026-11-22T09:15:30Z") },
            zone = { ZoneId.of("Europe/Paris") },
        )
    }

    @After
    fun tearDown() {
        silence.countDown()
        server.close()
    }

    /** The next request, waited for a while only: one that never comes fails the test instead of hanging it. */
    private fun nextRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(TimeoutMillis, TimeUnit.MILLISECONDS)) { "Nothing was sent" }

    private fun RecordedRequest.assertRevokes(key: Credential.ApiKey) {
        assertThat(method).isEqualTo("DELETE")
        assertThat(pathText()).isEqualTo("/api/v2/users/me/api-keys/${key.keyId}")
        assertThat(headers["X-API-Key"]).isEqualTo(key.key)
        assertThat(headers["Authorization"]).isNull()
    }

    /** A server that takes the request and never answers. */
    private fun silentServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                silence.await(TimeoutMillis * 2, TimeUnit.MILLISECONDS)
                return MockResponse(code = 204)
            }
        }
    }

    // --- signing out of the current profile

    @Test
    fun `signing out removes the current Komga profile, then revokes its key with the key itself`() =
        runBlocking<Unit> {
            store.current.value = Profile("p0", ServerKind.Komga, komgaSession(url))
            var currentSeenByServer: Profile? = store.current.value
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    currentSeenByServer = store.current.value
                    return MockResponse(code = 204)
                }
            }

            signIn.signOut("p0")

            assertThat(store.cleared).containsExactly("p0")
            assertThat(currentSeenByServer).isNull()
            nextRequest().assertRevokes(Credential.ApiKey(TheKey, TheKeyId))
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `a sign-out whose server does not answer still removes the profile, and gives up soon`() = runBlocking {
        store.current.value = Profile("p0", ServerKind.Komga, komgaSession(url))
        silentServer()

        withTimeout(TimeoutMillis) { signIn.signOut("p0") }

        assertThat(store.cleared).containsExactly("p0")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a sign-out still revokes the key when its caller is cancelled`() = runBlocking {
        store.current.value = Profile("p0", ServerKind.Komga, komgaSession(url))
        val reached = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                reached.complete(Unit)
                release.await(TimeoutMillis, TimeUnit.MILLISECONDS)
                return MockResponse(code = 204)
            }
        }
        var finished = false
        val signOut = launch(Dispatchers.IO) {
            signIn.signOut("p0")
            finished = true
        }

        withTimeout(TimeoutMillis) { reached.await() }
        signOut.cancel()
        release.countDown()
        withTimeout(TimeoutMillis) { signOut.join() }

        // The revocation ran to its answer although its caller was cancelled.
        assertThat(finished).isTrue()
    }

    /** The profile switch tears the screen down as soon as the profile is gone: its key must not stay valid. */
    @Test
    fun `a sign-out whose caller is cancelled as the profile is removed still revokes the key`() = runBlocking {
        store.current.value = Profile("p0", ServerKind.Komga, komgaSession(url))
        server.enqueueNoContent()
        val signOut = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { signIn.signOut("p0") }
        store.afterClear = {
            signOut.cancel()
            // The registry's write coming back to a caller cancelled meanwhile.
            yield()
        }

        signOut.start()
        withTimeout(TimeoutMillis) { signOut.join() }

        assertThat(store.cleared).containsExactly("p0")
        nextRequest().assertRevokes(Credential.ApiKey(TheKey, TheKeyId))
    }

    @Test
    fun `a sign-out of a profile that is not current, or of another kind, sends nothing`() = runBlocking {
        store.current.value = Profile("p1", ServerKind.Komga, komgaSession(url))
        signIn.signOut("p0")
        val tokens = Tokens("a", Instant.MAX, "r", Instant.MAX)
        val bookOrbit = bookOrbitSession(url, 1, tokens, UserProfile("1", "ann", "Ann", null))
        store.current.value = Profile("p0", ServerKind.BookOrbit, bookOrbit)
        signIn.signOut("p0")

        assertThat(store.cleared).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // --- an account added again

    private fun enqueueSignIn() {
        server.enqueueJson(AnnJson, 200, "X-Auth-Token" to "5e5510n")
        server.enqueueJson(KeyJson)
        server.enqueueNoContent()
    }

    @Test
    fun `an account added again revokes the key its profile held, once the new one is saved`() = runBlocking {
        store.replaced = ReplacedSession(OldKey, refused = false)
        enqueueSignIn()
        server.enqueueNoContent()

        signIn.signInWithPassword(url, "ann@example.com", "secret")

        assertThat(store.saved.single().first.credential).isEqualTo(Credential.ApiKey(TheKey, TheKeyId))
        repeat(3) { nextRequest() }
        nextRequest().assertRevokes(OldKey)
        assertThat(server.requestCount).isEqualTo(4)
    }

    /** The server refused that key: it is revoked already, or nothing can be done with it. */
    @Test
    fun `the key of an account added again is not sent when the server had refused it`() = runBlocking {
        store.replaced = ReplacedSession(OldKey, refused = true)
        enqueueSignIn()

        signIn.signInWithPassword(url, "ann@example.com", "secret")

        assertThat(store.saved).hasSize(1)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `an account added again through its provider revokes the key its profile held too`() = runBlocking {
        store.replaced = ReplacedSession(OldKey, refused = false)
        server.enqueueJson(AnnJson)
        server.enqueueJson(KeyJson)
        server.enqueueNoContent()
        server.enqueueNoContent()

        signIn.completeWithSession(url, "KOMGA-SESSION=c00k1e")

        repeat(3) { nextRequest() }
        nextRequest().assertRevokes(OldKey)
    }

    // --- a profile removed while it slept

    @Test
    fun `the key of a profile removed while it slept is revoked with itself, and nothing else is touched`() =
        runBlocking {
            server.enqueueNoContent()

            signIn.endCredential(url, OldKey)

            nextRequest().assertRevokes(OldKey)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(store.cleared).isEmpty()
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `a server that does not answer is given up soon`() = runBlocking {
        silentServer()

        withTimeout(TimeoutMillis) { signIn.endCredential(url, OldKey) }

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a server that refuses the revocation changes nothing for the app`() = runBlocking {
        server.enqueueJson("""{"status":401}""", code = 401)

        signIn.endCredential(url, OldKey)

        assertThat(server.requestCount).isEqualTo(1)
    }

    // --- what is told of a key left valid

    /** A revocation telling [told], given up soon. */
    private fun revocation(told: MutableList<String>) =
        KomgaRevocation(OkHttpClient(), KomgaJson, timeout = Duration.ofSeconds(1), log = { NetworkLog { told += it } })

    @Test
    fun `a refused revocation is told by its status and path, never with the key or the server`() = runBlocking {
        val told = mutableListOf<String>()
        server.enqueueJson("""{"status":403}""", code = 403)

        revocation(told).revoke(url, OldKey)

        val line = told.single()
        assertThat(line).contains("403")
        assertThat(line).contains("/api/v2/users/me/api-keys/${OldKey.keyId}")
        assertThat(line).doesNotContain(OldKey.key)
        assertThat(line).doesNotContain(server.hostName)
    }

    @Test
    fun `a revocation that never got its answer is told, never with the key or the server`() = runBlocking {
        val told = mutableListOf<String>()
        silentServer()

        withTimeout(TimeoutMillis) { revocation(told).revoke(url, OldKey) }

        val line = told.single()
        assertThat(line).doesNotContain(OldKey.key)
        assertThat(line).doesNotContain(server.hostName)
        assertThat(line).doesNotContain(server.port.toString())
    }

    @Test
    fun `a revoked key tells nothing`() = runBlocking {
        val told = mutableListOf<String>()
        server.enqueueNoContent()

        revocation(told).revoke(url, OldKey)

        assertThat(told).isEmpty()
    }
}
