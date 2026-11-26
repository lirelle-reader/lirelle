package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.source.kavita.testing.Ann
import app.tfdn.lirelle.source.kavita.testing.MemoryStore
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.annJsonWithNullVersion
import app.tfdn.lirelle.source.kavita.testing.annJsonWithoutVersion
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.enqueueRefusal
import app.tfdn.lirelle.source.kavita.testing.enqueueText
import app.tfdn.lirelle.source.kavita.testing.kavitaSignIn
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A key the user made in Kavita's web interface. */
private const val Pasted = "p45t3d-k3y-0f-th3-us3r"

/** What names [Pasted] when the server does not: the beginning of its SHA-256, never the key. */
private val UnnamedId = "pasted-" + MessageDigest.getInstance("SHA-256").digest(Pasted.toByteArray())
    .joinToString("") { "%02x".format(it) }.take(12)

/** The account's keys as Kavita lists them, each in clear: another one, then the pasted one, named 9. */
private const val KeysJson = """[{"id":3,"key":"an0th3r-k3y","name":"OPDS","provider":0},""" +
    """{"id":9,"key":"$Pasted","name":"Lirelle on my tablet","provider":0,"expiresAtUtc":null}]"""

/**
 * Signing in to Kavita with a key the user pasted: the account is read with it, on a server recent enough, the key
 * is named as the server names it among the account's keys, and a Kavita profile is saved with it. The key stays
 * the user's: nothing is made on the server, and nothing will be deleted there.
 */
class KavitaKeySignInTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private lateinit var url: String
    private lateinit var signIn: KavitaSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = kavitaSignIn(store)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun saved(keyId: String) =
        Session(url, Credential.ApiKey(Pasted, keyId, ownedByApp = false), Ann) to ServerKind.Kavita

    @Test
    fun `a pasted key reads the account, finds its name among the account's keys, and saves a Kavita profile`() =
        runBlocking<Unit> {
            server.enqueueJson(annJson())
            server.enqueueJson(KeysJson)

            assertThat(signIn.signInWithKey(url, Pasted)).isEqualTo(ApiResult.Success(Unit))

            val account = server.takeRequest()
            assertThat(account.method).isEqualTo("GET")
            assertThat(account.pathText()).isEqualTo("/api/Account")
            assertThat(account.headers["x-api-key"]).isEqualTo(Pasted)
            assertThat(account.headers["Authorization"]).isNull()
            assertThat(account.headers["Cookie"]).isNull()
            val keys = server.takeRequest()
            assertThat(keys.method).isEqualTo("GET")
            assertThat(keys.pathText()).isEqualTo("/api/Account/auth-keys")
            assertThat(keys.headers["x-api-key"]).isEqualTo(Pasted)
            assertThat(keys.headers["Authorization"]).isNull()
            // Nothing is made: the key is the user's.
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(store.saved).containsExactly(saved(keyId = "9"))
            assertThat((store.saved.single().first.credential as Credential.ApiKey).ownedByApp).isFalse()
            assertThat(store.saved.toString()).doesNotContain(Pasted)
        }

    @Test
    fun `a key pasted with spaces or a line break around it is taken without them`() = runBlocking<Unit> {
        server.enqueueJson(annJson())
        server.enqueueJson(KeysJson)

        assertThat(signIn.signInWithKey(url, "  $Pasted\n")).isEqualTo(ApiResult.Success(Unit))

        assertThat(server.takeRequest().headers["x-api-key"]).isEqualTo(Pasted)
        assertThat(store.saved).containsExactly(saved(keyId = "9"))
    }

    @Test
    fun `no key at all is a key refused, and nothing is sent`() = runBlocking {
        assertThat(signIn.signInWithKey(url, "")).isEqualTo(ApiResult.Failure(ApiError.KeyRefused))
        assertThat(signIn.signInWithKey(url, " \n\t")).isEqualTo(ApiResult.Failure(ApiError.KeyRefused))

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a key the server does not know is a key refused, and nothing is saved`() = runBlocking {
        server.enqueueRefusal()

        assertThat(signIn.signInWithKey(url, Pasted)).isEqualTo(ApiResult.Failure(ApiError.KeyRefused))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a server too old says so with its version, and nothing is saved`() = runBlocking {
        server.enqueueJson(annJson(version = "0.8.8.3"))

        assertThat(signIn.signInWithKey(url, Pasted))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld("0.8.8.3", "0.8.9")))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a server that tells no version in numbers is refused, and nothing is saved`() = runBlocking {
        val untold = listOf(annJsonWithoutVersion(), annJsonWithNullVersion(), annJson(version = "nightly"))
        untold.forEach { answer ->
            server.enqueueJson(answer)

            assertThat(signIn.signInWithKey(url, Pasted)).isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
        }
        assertThat(server.requestCount).isEqualTo(untold.size)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a version with a suffix is compared by its numbers, and told as the server said it`() = runBlocking {
        server.enqueueJson(annJson(version = "0.8.8.3-beta"))
        assertThat(signIn.signInWithKey(url, Pasted))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld("0.8.8.3-beta", "0.8.9")))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()

        server.enqueueJson(annJson(version = "0.9.1.4-beta"))
        server.enqueueJson(KeysJson)
        assertThat(signIn.signInWithKey(url, Pasted)).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.saved).hasSize(1)
    }

    /** Two keys the server does not name must not pass for one another: each is named after itself, never by itself. */
    @Test
    fun `two keys the server does not name are told apart, and the same key is named the same`() = runBlocking<Unit> {
        listOf(Pasted, "an0th3r-p45t3d-k3y", Pasted).forEach { key ->
            server.enqueueJson(annJson())
            server.enqueueRefusal()
            signIn.signInWithKey(url, key)
        }

        val ids = store.saved.map { (it.first.credential as Credential.ApiKey).keyId }
        assertThat(ids[0]).isEqualTo(UnnamedId)
        assertThat(ids[1]).matches("pasted-[0-9a-f]{12}")
        assertThat(ids[1]).isNotEqualTo(ids[0])
        assertThat(ids[2]).isEqualTo(ids[0])
        assertThat(store.saved[0].first.credential.isSameSignIn(store.saved[1].first.credential)).isFalse()
        ids.forEach { assertThat(it).doesNotContain(Pasted) }
    }

    @Test
    fun `a failing server is a failure, and nothing is saved`() = runBlocking {
        server.enqueueText("System.Exception\n   at API.Startup()", code = 500)

        assertThat(signIn.signInWithKey(url, Pasted)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `keys that cannot be listed leave the pasted key without the server's name for it`() = runBlocking<Unit> {
        server.enqueueJson(annJson())
        server.enqueueText("System.Exception\n   at API.Controllers.Account()", code = 500)

        assertThat(signIn.signInWithKey(url, Pasted)).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.saved).containsExactly(saved(keyId = UnnamedId))
    }

    @Test
    fun `a list that is refused, or is no list, leaves the pasted key without the server's name for it`() =
        runBlocking<Unit> {
            server.enqueueJson(annJson())
            server.enqueueRefusal()
            signIn.signInWithKey(url, Pasted)
            server.enqueueJson(annJson())
            server.enqueueJson("""{"keys":"none"}""")
            signIn.signInWithKey(url, Pasted)

            assertThat(store.saved).containsExactly(saved(keyId = UnnamedId), saved(keyId = UnnamedId))
        }

    @Test
    fun `a list that does not hold the pasted key leaves it without the server's name for it`() = runBlocking<Unit> {
        server.enqueueJson(annJson())
        server.enqueueJson("""[{"id":3,"key":"an0th3r-k3y","name":"OPDS","provider":0}]""")

        assertThat(signIn.signInWithKey(url, Pasted)).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.saved).containsExactly(saved(keyId = UnnamedId))
    }

    @Test
    fun `a profile that cannot be saved with a pasted key deletes nothing`() = runBlocking {
        store.failSaves = true
        server.enqueueJson(annJson())
        server.enqueueJson(KeysJson)

        assertThat(signIn.signInWithKey(url, Pasted)).isInstanceOf(ApiResult.Failure::class.java)

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(store.saved).isEmpty()
    }
}
