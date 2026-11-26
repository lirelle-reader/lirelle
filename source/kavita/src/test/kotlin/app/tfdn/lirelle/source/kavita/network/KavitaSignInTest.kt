package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyDto
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto
import app.tfdn.lirelle.source.kavita.network.dto.LoginRequest
import app.tfdn.lirelle.source.kavita.testing.Ann
import app.tfdn.lirelle.source.kavita.testing.KeyJson
import app.tfdn.lirelle.source.kavita.testing.KeyMadeAt
import app.tfdn.lirelle.source.kavita.testing.KeyName
import app.tfdn.lirelle.source.kavita.testing.MemoryStore
import app.tfdn.lirelle.source.kavita.testing.NameTakenText
import app.tfdn.lirelle.source.kavita.testing.OidcOffJson
import app.tfdn.lirelle.source.kavita.testing.PreciseKeyName
import app.tfdn.lirelle.source.kavita.testing.TheKey
import app.tfdn.lirelle.source.kavita.testing.TheKeyId
import app.tfdn.lirelle.source.kavita.testing.TheRefreshToken
import app.tfdn.lirelle.source.kavita.testing.TheToken
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.annJsonWithNullVersion
import app.tfdn.lirelle.source.kavita.testing.annJsonWithoutVersion
import app.tfdn.lirelle.source.kavita.testing.bodyText
import app.tfdn.lirelle.source.kavita.testing.enqueueDone
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.enqueueRefusal
import app.tfdn.lirelle.source.kavita.testing.enqueueText
import app.tfdn.lirelle.source.kavita.testing.kavitaSession
import app.tfdn.lirelle.source.kavita.testing.kavitaSignIn
import app.tfdn.lirelle.source.kavita.testing.oidcOnJson
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encoding.CompositeDecoder
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Username = "ann"
private const val Password = "s3cr3t-p4ssw0rd"

/**
 * Signing in to Kavita by username and password: the session the password opens, on a server recent enough, makes
 * a key of the device's own whose name says the app, the device and the time, and a Kavita profile is saved with
 * the key. The password and the session's tokens are kept nowhere. Once the key exists, nothing stops the profile
 * from being saved, and a profile that cannot be saved takes its key with it.
 */
class KavitaSignInTest {

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

    private fun enqueueLogin(version: String = "0.9.1.4") = server.enqueueJson(annJson(version, withTokens = true))

    private val theKey = Credential.ApiKey(TheKey, TheKeyId, ownedByApp = true)

    @Test
    fun `a password sign-in opens a session, makes a key of its own with it, and saves a Kavita profile`() =
        runBlocking<Unit> {
            enqueueLogin()
            server.enqueueJson(KeyJson)

            assertThat(signIn.signInWithPassword(url, Username, Password)).isEqualTo(ApiResult.Success(Unit))

            val login = server.takeRequest()
            assertThat(login.method).isEqualTo("POST")
            assertThat(login.pathText()).isEqualTo("/api/Account/login")
            assertThat(login.bodyText()).isEqualTo("""{"username":"ann","password":"$Password","apiKey":""}""")
            assertThat(login.headers["Authorization"]).isNull()
            assertThat(login.headers["x-api-key"]).isNull()
            val mint = server.takeRequest()
            assertThat(mint.method).isEqualTo("POST")
            assertThat(mint.pathText()).isEqualTo("/api/Account/create-auth-key")
            assertThat(mint.headers["Authorization"]).isEqualTo("Bearer $TheToken")
            assertThat(mint.headers["Cookie"]).isNull()
            assertThat(mint.bodyText()).isEqualTo("""{"keyLength":32,"name":"$KeyName","expiresUtc":null}""")
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(store.saved).containsExactly(Session(url, theKey, Ann) to ServerKind.Kavita)
            assertThat((store.saved.single().first.credential as Credential.ApiKey).ownedByApp).isTrue()
        }

    @Test
    fun `nothing of the password or of the session's tokens is in what is saved`() = runBlocking {
        enqueueLogin()
        server.enqueueJson(KeyJson)

        signIn.signInWithPassword(url, Username, Password)

        val (session, _) = store.saved.single()
        val kept =
            listOf(session.serverUrl, session.user.toString(), session.toString(), store.current.value.toString())
        val credential = session.credential as Credential.ApiKey
        (kept + credential.key + credential.keyId).forEach {
            assertThat(it).doesNotContain(Password)
            assertThat(it).doesNotContain(TheToken)
            assertThat(it).doesNotContain(TheRefreshToken)
        }
        assertThat(session.toString()).doesNotContain(TheKey)
    }

    @Test
    fun `a server under a base path is signed in to under it`() = runBlocking {
        enqueueLogin()
        server.enqueueJson(KeyJson)

        signIn.signInWithPassword("$url/kavita", Username, Password)

        assertThat(server.takeRequest().pathText()).isEqualTo("/kavita/api/Account/login")
        assertThat(server.takeRequest().pathText()).isEqualTo("/kavita/api/Account/create-auth-key")
        assertThat(store.saved.single().first.serverUrl).isEqualTo("$url/kavita")
    }

    @Test
    fun `a wrong username or password is wrong credentials, and nothing is made nor saved`() = runBlocking {
        server.enqueueText("Your credentials are not correct", code = 401)

        assertThat(signIn.signInWithPassword(url, Username, "wrong"))
            .isEqualTo(ApiResult.Failure(ApiError.InvalidCredentials))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a server too old says so with its version, and no key is made nor anything saved`() = runBlocking {
        enqueueLogin(version = "0.8.8.3")

        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld("0.8.8.3", "0.8.9")))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()
    }

    /** A real Kavita always tells its version in numbers: an answer without one is not trusted to be recent. */
    @Test
    fun `a server that tells no version in numbers is refused, and no key is made nor anything saved`() =
        runBlocking {
            val untold = listOf(
                annJsonWithoutVersion(withTokens = true),
                annJsonWithNullVersion(withTokens = true),
                annJson(version = "", withTokens = true),
                annJson(version = "nightly", withTokens = true),
            )
            untold.forEach { answer ->
                server.enqueueJson(answer)

                assertThat(signIn.signInWithPassword(url, Username, Password))
                    .isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
            }
            assertThat(server.requestCount).isEqualTo(untold.size)
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `a version with a suffix is compared by its numbers, and told as the server said it`() = runBlocking {
        enqueueLogin(version = "0.8.8.3-beta")
        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld("0.8.8.3-beta", "0.8.9")))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()

        enqueueLogin(version = "0.9.1.4-beta")
        server.enqueueJson(KeyJson)
        assertThat(signIn.signInWithPassword(url, Username, Password)).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.saved).hasSize(1)
    }

    /** The password is in the body of the sign-in, which a 307 and a 308 keep: it goes to no other server. */
    @Test
    fun `a sign-in redirected to another server fails as an unreachable server, and nothing is sent there`() =
        runBlocking {
            MockWebServer().use { elsewhere ->
                elsewhere.start()
                listOf(307, 308).forEach { code ->
                    val there = elsewhere.url("/api/Account/login").toString()
                    server.enqueue(MockResponse(code = code, headers = headersOf("Location", there)))

                    val result = signIn.signInWithPassword(url, Username, Password)

                    assertThat(result).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
                    assertThat(result.toString()).doesNotContain(Password)
                }
                assertThat(elsewhere.requestCount).isEqualTo(0)
            }
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `a session the server no longer takes when the key is asked for is a sign-in that did not succeed`() =
        runBlocking {
            enqueueLogin()
            server.enqueueRefusal()

            assertThat(signIn.signInWithPassword(url, Username, Password))
                .isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `a session answered without its token makes nothing`() = runBlocking {
        server.enqueueJson(annJson(withTokens = false))

        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a key whose name is taken is asked for once more, named to the millisecond`() = runBlocking {
        enqueueLogin()
        server.enqueueText(NameTakenText, code = 400)
        server.enqueueJson(KeyJson)

        assertThat(signIn.signInWithPassword(url, Username, Password)).isEqualTo(ApiResult.Success(Unit))

        server.takeRequest()
        assertThat(server.takeRequest().bodyText()).contains(""""name":"$KeyName",""")
        val again = server.takeRequest()
        assertThat(again.pathText()).isEqualTo("/api/Account/create-auth-key")
        assertThat(again.headers["Authorization"]).isEqualTo("Bearer $TheToken")
        assertThat(again.bodyText()).isEqualTo("""{"keyLength":32,"name":"$PreciseKeyName","expiresUtc":null}""")
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(store.saved.single().first.credential).isEqualTo(theKey)
    }

    @Test
    fun `a key whose name is taken twice is a sign-in that did not succeed, and is not asked for a third time`() =
        runBlocking {
            enqueueLogin()
            server.enqueueText(NameTakenText, code = 400)
            server.enqueueText(NameTakenText, code = 400)

            assertThat(signIn.signInWithPassword(url, Username, Password))
                .isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `a key refused for another reason is a sign-in that did not succeed, and is not asked for again`() =
        runBlocking {
            enqueueLogin()
            server.enqueueText("The key length is not valid", code = 400)

            assertThat(signIn.signInWithPassword(url, Username, Password))
                .isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `an account that may not make keys is a sign-in that did not succeed`() = runBlocking {
        enqueueLogin()
        server.enqueue(MockResponse(code = 403))

        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a failing server says nothing of what it answered`() = runBlocking {
        server.enqueueText("System.NullReferenceException\n   at API.Controllers.AccountController.Login()", code = 500)

        val failure = signIn.signInWithPassword(url, Username, Password)

        assertThat(failure).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(failure.toString()).doesNotContain("AccountController")
    }

    @Test
    fun `each sign-in names its key by the time it was made`() = runBlocking {
        var now = KeyMadeAt
        val later = kavitaSignIn(store, now = { now })
        repeat(2) {
            enqueueLogin()
            server.enqueueJson(KeyJson)
        }

        later.signInWithPassword(url, Username, Password)
        now = now.plusSeconds(1)
        later.signInWithPassword(url, Username, Password)

        val names = (1..4).map { server.takeRequest() }.filter { it.pathText().endsWith("/create-auth-key") }
            .map { it.bodyText() }
        assertThat(names).containsExactly(
            """{"keyLength":32,"name":"Lirelle — Pixel 9 — 2026-11-22 10:15:30","expiresUtc":null}""",
            """{"keyLength":32,"name":"Lirelle — Pixel 9 — 2026-11-22 10:15:31","expiresUtc":null}""",
        ).inOrder()
    }

    @Test
    fun `a profile that cannot be saved deletes its key at once, with the key itself`() = runBlocking {
        store.failSaves = true
        enqueueLogin()
        server.enqueueJson(KeyJson)
        server.enqueueDone()

        val result = signIn.signInWithPassword(url, Username, Password)

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
        repeat(2) { server.takeRequest() }
        val delete = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The key was not deleted" }
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.pathText()).isEqualTo("/api/Account/auth-key")
        assertThat(delete.url.queryParameter("authKeyId")).isEqualTo(TheKeyId)
        assertThat(delete.headers["x-api-key"]).isEqualTo(TheKey)
        assertThat(delete.headers["Authorization"]).isNull()
        assertThat(store.saved).isEmpty()
    }

    /** The key the account's profile held stays that profile's: only the new one, which nobody holds, goes. */
    @Test
    fun `a profile added again that cannot be saved deletes the new key only`() = runBlocking {
        store.failSaves = true
        store.replaced = ReplacedSession(Credential.ApiKey("0ld-k3y-0f-th3-d3v1c3", "17"), refused = false)
        enqueueLogin()
        server.enqueueJson(KeyJson)
        server.enqueueDone()

        assertThat(signIn.signInWithPassword(url, Username, Password)).isInstanceOf(ApiResult.Failure::class.java)

        repeat(2) { server.takeRequest() }
        val delete = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The key was not deleted" }
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.url.queryParameter("authKeyId")).isEqualTo(TheKeyId)
        assertThat(delete.headers["x-api-key"]).isEqualTo(TheKey)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(store.saved).isEmpty()
    }

    /** The key exists on the server from the moment it is asked for: a profile must hold it, or nobody does. */
    @Test
    fun `leaving the screen once the key is asked for still saves the profile`() = runBlocking {
        val minting = CountDownLatch(1)
        val left = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.pathText().endsWith("/create-auth-key") -> {
                    minting.countDown()
                    left.await(5, TimeUnit.SECONDS)
                    json(KeyJson)
                }
                else -> json(annJson(withTokens = true))
            }
        }

        val screen = launch(Dispatchers.IO) { signIn.signInWithPassword(url, Username, Password) }
        assertThat(minting.await(5, TimeUnit.SECONDS)).isTrue()
        screen.cancel()
        left.countDown()
        screen.join()

        assertThat(store.saved.map { it.first.credential }).containsExactly(theKey)
        assertThat(server.requestCount).isEqualTo(2)
    }

    private val provider = OidcProvider("oidc", "Authentik", "", "", ProviderFlow.WebView)

    private fun options(passwordEnabled: Boolean, providers: List<OidcProvider>) = ApiResult.Success(
        LoginOptions(passwordEnabled, providers, identifier = SignInIdentifier.Username, keyEntry = true),
    )

    @Test
    fun `without a provider, the options are a username and a password, and a pasted key`() = runBlocking {
        server.enqueueJson(OidcOffJson)

        assertThat(signIn.loginOptions(url)).isEqualTo(options(passwordEnabled = true, providers = emptyList()))
        val asked = server.takeRequest()
        assertThat(asked.pathText()).isEqualTo("/api/Settings/oidc")
        assertThat(asked.headers["Authorization"]).isNull()
    }

    @Test
    fun `with a provider, the options offer it through a WebView, by the name the server gives it`() = runBlocking {
        server.enqueueJson(oidcOnJson("Authentik"))

        assertThat(signIn.loginOptions(url)).isEqualTo(options(passwordEnabled = true, providers = listOf(provider)))
    }

    /** Kavita's own page names it so when the server gives no name. */
    @Test
    fun `a provider the server gives no name is called OpenID Connect`() = runBlocking {
        listOf("", "   ").forEach { name ->
            server.enqueueJson(oidcOnJson(name))

            val options = (signIn.loginOptions(url) as ApiResult.Success).value

            assertThat(options.oidcProviders.single().displayName).isEqualTo("OpenID Connect")
        }
    }

    @Test
    fun `with a provider and passwords no longer taken, the options take no password`() = runBlocking {
        server.enqueueJson(oidcOnJson("Authentik", passwordsDisabled = true))

        assertThat(signIn.loginOptions(url)).isEqualTo(options(passwordEnabled = false, providers = listOf(provider)))
    }

    @Test
    fun `options that cannot be read are a failure`() = runBlocking {
        server.enqueueText("Bad gateway", code = 502)
        assertThat(signIn.loginOptions(url)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))

        server.enqueueJson("[]")
        assertThat(signIn.loginOptions(url)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `options longer than any of Kavita's are a failure`() = runBlocking {
        server.enqueueJson(OidcOffJson + " ".repeat(100_000))

        assertThat(signIn.loginOptions(url)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `the provider's sign-in begins at Kavita's own address for it, under the base path`() = runBlocking {
        assertThat(signIn.beginOidc("https://bd.example.com/kavita", provider))
            .isEqualTo(ApiResult.Success("https://bd.example.com/kavita/oidc/login"))
        assertThat(signIn.beginOidc("https://bd.example.com", provider))
            .isEqualTo(ApiResult.Success("https://bd.example.com/oidc/login"))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `no browser redirect is ever Kavita's`() = runBlocking {
        val answer = "bookorbit://oauth2-callback?code=c&state=s"

        assertThat(signIn.hasPendingOidc()).isFalse()
        assertThat(signIn.answersPendingOidc(answer)).isFalse()
        assertThat(signIn.completeOidc(answer)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        signIn.cancelOidc()
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** Its key deleted after, as [KavitaKeyEndingTest] shows. */
    @Test
    fun `a sign-out removes the current Kavita profile`() = runBlocking<Unit> {
        store.current.value = Profile("p0", ServerKind.Kavita, kavitaSession(url))
        server.enqueueDone()

        signIn.signOut("p0")

        assertThat(store.cleared).containsExactly("p0")
    }

    @Test
    fun `a sign-out leaves a profile of another kind, or one that is not current, as it is`() = runBlocking {
        val bookOrbit = bookOrbitSession(url, 1, Tokens("a", Instant.MAX, "r", Instant.MAX), Ann)
        store.current.value = Profile("p0", ServerKind.BookOrbit, bookOrbit)
        signIn.signOut("p0")
        store.current.value = Profile("p0", ServerKind.Komga, kavitaSession(url))
        signIn.signOut("p0")
        store.current.value = Profile("p1", ServerKind.Kavita, kavitaSession(url))
        signIn.signOut("p0")

        assertThat(store.cleared).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `what is sent and answered never prints a password, a token or a key`() {
        val api = KavitaApis(OkHttpClient(), KavitaJson, KavitaPublicApi::class.java).forServer(url)
        val printed = listOf(
            LoginRequest(Username, Password, apiKey = TheKey),
            KavitaUserDto(7, Username, token = TheToken),
            AuthKeyDto(42, TheKey),
            KavitaSessionProof.byPassword(api, Username, Password),
            KavitaSessionProof.ofSession(api, ".AspNetCore.Cookies=$TheToken"),
        ).map { it.toString() }

        printed.forEach { text ->
            listOf(Password, TheToken, TheRefreshToken, TheKey).forEach { assertThat(text).doesNotContain(it) }
        }
    }

    /** The refresh token of the session a password opened is not even read: it is held nowhere. */
    @Test
    fun `the session's refresh token is not read`() {
        val account = KavitaJson.decodeFromString(KavitaUserDto.serializer(), annJson(withTokens = true))

        assertThat(account.token).isEqualTo(TheToken)
        assertThat(KavitaUserDto.serializer().descriptor.getElementIndex("refreshToken"))
            .isEqualTo(CompositeDecoder.UNKNOWN_NAME)
    }

    private fun json(body: String): MockResponse =
        MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)
}
