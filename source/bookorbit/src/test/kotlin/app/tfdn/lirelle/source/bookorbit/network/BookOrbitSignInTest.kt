package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.Pkce
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.testing.InMemoryPendingOidcStore
import app.tfdn.lirelle.source.bookorbit.network.testing.InMemorySessionStore
import app.tfdn.lirelle.source.bookorbit.network.testing.bodyText
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueFixture
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.network.testing.pathText
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.testing.sessionId
import app.tfdn.lirelle.source.bookorbit.network.testing.testSession
import app.tfdn.lirelle.source.bookorbit.network.testing.tokens
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Signing in to a BookOrbit server, and out of a BookOrbit profile. Its probe is [BookOrbitProbeTest]'s. */
class BookOrbitSignInTest {

    private val server = MockWebServer()
    private val store = InMemorySessionStore()
    private val pendingOidc = InMemoryPendingOidcStore()
    private lateinit var repository: BookOrbitSignIn
    private lateinit var url: String

    private val provider = OidcProvider("authentik", "Authentik", "client-id-1", "openid profile email")

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        val client = OkHttpClient()
        repository = BookOrbitSignIn(
            store = store,
            pendingOidc = pendingOidc,
            publicApi = ApiProvider(client, ApiJson, PublicApi::class.java),
            errors = ErrorMapper(ApiJson),
            deviceLabel = { "Phone (Lirelle)" },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun field(json: String, name: String): String? =
        ApiJson.decodeFromString<JsonObject>(json)[name]?.jsonPrimitive?.content

    private fun pending(state: String = "state-1") =
        PendingOidcRequest(url, "authentik", state, "verifier-1", "nonce-1", System.currentTimeMillis())

    // --- loginOptions

    @Test
    fun `login options list enabled providers only`() = runBlocking<Unit> {
        server.enqueueFixture("login-options.json")

        val result = repository.loginOptions(url)

        assertThat(result)
            .isEqualTo(ApiResult.Success(LoginOptions(passwordEnabled = true, oidcProviders = listOf(provider))))
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/login-options")
        // A username with the password; its providers through the browser, back by the app's redirect.
        val options = (result as ApiResult.Success).value
        assertThat(options.identifier).isEqualTo(SignInIdentifier.Username)
        assertThat(options.oidcProviders.map { it.signsInBy }).containsExactly(ProviderFlow.Browser)
    }

    // --- signInWithPassword

    @Test
    fun `password sign-in stores the session`() = runBlocking {
        server.enqueueFixture("native-auth.json")

        val result = repository.signInWithPassword(url, "reader", "secret")

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        val session = store.session!!
        assertThat(session.serverUrl).isEqualTo(url)
        assertThat(session.sessionId).isEqualTo(42)
        assertThat(session.tokens.accessToken).isEqualTo("access-1")
        assertThat(session.tokens.accessTokenExpiresAt).isEqualTo(Instant.parse("2026-11-13T08:15:00Z"))
        assertThat(session.tokens.refreshToken).isEqualTo("1".repeat(64))
        assertThat(session.user.name).isEqualTo("Ann Reader")
        assertThat(session.user.email).isEqualTo("ann@example.com")
    }

    @Test
    fun `a sign-in makes a profile of a BookOrbit server`() = runBlocking {
        server.enqueueFixture("native-auth.json")

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(store.current.value?.kind).isEqualTo(ServerKind.BookOrbit)
    }

    @Test
    fun `the account's id, a number for the server, is its text for the app`() = runBlocking {
        server.enqueueFixture("native-auth.json")

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(store.session!!.user.id).isEqualTo("1")
    }

    @Test
    fun `password sign-in declares a native client and the device label`() = runBlocking {
        server.enqueueFixture("native-auth.json")

        repository.signInWithPassword(url, "reader", "secret")

        val request = server.takeRequest()
        assertThat(request.pathText()).isEqualTo("/api/v1/auth/login")
        assertThat(field(request.bodyText(), "username")).isEqualTo("reader")
        assertThat(field(request.bodyText(), "password")).isEqualTo("secret")
        assertThat(field(request.bodyText(), "clientKind")).isEqualTo("native")
        assertThat(field(request.bodyText(), "deviceLabel")).isEqualTo("Phone (Lirelle)")
    }

    @Test
    fun `wrong credentials are reported and store nothing`() = runBlocking {
        server.enqueueJson("""{"statusCode":401,"message":"Invalid credentials"}""", code = 401)

        assertThat(repository.signInWithPassword(url, "reader", "wrong"))
            .isEqualTo(ApiResult.Failure(ApiError.InvalidCredentials))
        assertThat(store.session).isNull()
    }

    @Test
    fun `a locked account reports the wait time`() = runBlocking {
        server.enqueueFixture("error-account-locked.json", code = 401)

        assertThat(repository.signInWithPassword(url, "reader", "secret"))
            .isEqualTo(ApiResult.Failure(ApiError.AccountLocked(540)))
    }

    @Test
    fun `throttling is reported`() = runBlocking {
        server.enqueueJson("""{"statusCode":429}""", code = 429)

        assertThat(repository.signInWithPassword(url, "reader", "secret"))
            .isEqualTo(ApiResult.Failure(ApiError.TooManyAttempts))
    }

    @Test
    fun `a default password is refused and the new session is closed`() = runBlocking {
        server.enqueueJson(
            Fixtures.read("native-auth.json").replace("\"isDefaultPassword\":false", "\"isDefaultPassword\":true"),
        )
        server.enqueueJson("{}")

        val result = repository.signInWithPassword(url, "admin", "admin")

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.DefaultPasswordMustChange))
        assertThat(store.session).isNull()
        server.takeRequest()
        val logout = server.takeRequest()
        assertThat(logout.pathText()).isEqualTo("/api/v1/auth/logout")
        assertThat(field(logout.bodyText(), "refreshToken")).isEqualTo("1".repeat(64))
    }

    // --- OIDC

    @Test
    fun `beginning oidc returns the authorization url and remembers the request`() = runBlocking {
        server.enqueueFixture("oidc-state.json")

        val result = repository.beginOidc(url, provider)

        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/oidc/authentik/state")
        val saved = pendingOidc.pending!!
        assertThat(saved.serverUrl).isEqualTo(url)
        assertThat(saved.providerSlug).isEqualTo("authentik")
        assertThat(saved.state).isEqualTo("state-1")
        val start = (result as ApiResult.Success).value
        // The Auth Tab watches for BookOrbit's scheme, that of the only redirect its server takes.
        assertThat(start.redirectScheme).isEqualTo("bookorbit")
        val authorizationUrl = start.url.toHttpUrl()
        assertThat(authorizationUrl.host).isEqualTo("auth.example.com")
        assertThat(authorizationUrl.queryParameter("client_id")).isEqualTo("client-id-1")
        assertThat(authorizationUrl.queryParameter("scope")).isEqualTo("openid profile email")
        assertThat(authorizationUrl.queryParameter("state")).isEqualTo("state-1")
        assertThat(authorizationUrl.queryParameter("nonce")).isEqualTo(saved.nonce)
        assertThat(authorizationUrl.queryParameter("code_challenge")).isEqualTo(Pkce.challenge(saved.codeVerifier))
        assertThat(authorizationUrl.queryParameter("redirect_uri")).isEqualTo("bookorbit://oauth2-callback")
    }

    @Test
    fun `completing oidc exchanges the code and stores the session`() = runBlocking {
        pendingOidc.pending = pending()
        server.enqueueFixture("native-auth.json")

        val result = repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=state-1")

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.session?.tokens?.accessToken).isEqualTo("access-1")
        val request = server.takeRequest()
        assertThat(request.pathText()).isEqualTo("/api/v1/auth/oidc/callback")
        assertThat(field(request.bodyText(), "code")).isEqualTo("code-1")
        assertThat(field(request.bodyText(), "codeVerifier")).isEqualTo("verifier-1")
        assertThat(field(request.bodyText(), "redirectUri")).isEqualTo("bookorbit://oauth2-callback")
        assertThat(field(request.bodyText(), "nonce")).isEqualTo("nonce-1")
        assertThat(field(request.bodyText(), "state")).isEqualTo("state-1")
        assertThat(field(request.bodyText(), "clientKind")).isEqualTo("native")
        assertThat(field(request.bodyText(), "deviceLabel")).isEqualTo("Phone (Lirelle)")
    }

    @Test
    fun `oidc sign-in is accepted for an account that still has its default password`() = runBlocking {
        // The server exempts sessions opened through the identity provider from the password gate.
        pendingOidc.pending = pending()
        server.enqueueJson(
            Fixtures.read("native-auth.json").replace("\"isDefaultPassword\":false", "\"isDefaultPassword\":true"),
        )

        val result = repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=state-1")

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.session?.tokens?.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a redirect whose state does not match is refused without calling the server`() = runBlocking {
        val waiting = pending(state = "state-1")
        pendingOidc.pending = waiting

        val result = repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=other")

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(server.requestCount).isEqualTo(0)
        // Not its answer: the sign-in still waits for it.
        assertThat(pendingOidc.pending).isEqualTo(waiting)
    }

    @Test
    fun `a redirect carrying an error is refused`() = runBlocking {
        pendingOidc.pending = pending()

        assertThat(repository.completeOidc("bookorbit://oauth2-callback?error=access_denied&state=state-1"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** BookOrbit's providers sign in through the browser, which comes back with a redirect: never a WebView's. */
    @Test
    fun `no provider's page in a WebView is BookOrbit's, and a waiting sign-in still waits`() = runBlocking {
        val waiting = pending()
        pendingOidc.pending = waiting

        assertThat(repository.completeWithSession(url, "SESSION=s1"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.current.value).isNull()
        assertThat(pendingOidc.pending).isEqualTo(waiting)
    }

    /** BookOrbit makes the session itself, out of a password or a provider: a key pasted is not its own. */
    @Test
    fun `a pasted key is not BookOrbit's, and nothing is sent`() = runBlocking {
        assertThat(repository.signInWithKey(url, "pasted-key")).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.current.value).isNull()
    }

    @Test
    fun `a malformed redirect is refused instead of failing`() = runBlocking {
        pendingOidc.pending = pending()

        assertThat(repository.completeOidc("bookorbit://oauth2-callback?code=%zz&state=state-1"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a redirect with nothing pending is refused`() = runBlocking {
        assertThat(repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=state-1"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a callback the server rejects is refused`() = runBlocking {
        pendingOidc.pending = pending()
        server.enqueueJson("""{"statusCode":401,"errorCode":"oidc_user_not_provisioned"}""", code = 401)

        assertThat(repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=state-1"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(store.session).isNull()
    }

    @Test
    fun `an oidc sign-in is pending from its beginning to its redirect`() = runBlocking {
        assertThat(repository.hasPendingOidc()).isFalse()

        server.enqueueFixture("oidc-state.json")
        repository.beginOidc(url, provider)
        assertThat(repository.hasPendingOidc()).isTrue()

        server.enqueueFixture("native-auth.json")
        repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=state-1")
        assertThat(repository.hasPendingOidc()).isFalse()
    }

    @Test
    fun `a cancelled oidc sign-in is not pending`() = runBlocking {
        pendingOidc.pending = pending()

        repository.cancelOidc()

        assertThat(repository.hasPendingOidc()).isFalse()
    }

    @Test
    fun `cancelling oidc forgets the pending request`() = runBlocking {
        pendingOidc.pending = pending()

        repository.cancelOidc()

        assertThat(pendingOidc.pending).isNull()
    }

    // --- signOut

    /** The profile the app is on: the one a sign-out is asked about. */
    private fun current(): String = checkNotNull(store.current.value).id

    /** Another account of the same server, signed in with a session of its own. */
    private fun otherAccount() = testSession(url).let {
        it.copy(credential = Credential.SessionTokens(43, it.tokens), user = it.user.copy(id = "2"))
    }

    @Test
    fun `sign-out tells the server and clears the session`() = runBlocking {
        store.save(testSession(url))
        server.enqueueJson("{}")

        repository.signOut(current())

        val request = server.takeRequest()
        assertThat(request.pathText()).isEqualTo("/api/v1/auth/logout")
        assertThat(field(request.bodyText(), "refreshToken")).isEqualTo("1".repeat(64))
        assertThat(store.state.value).isEqualTo(SessionState.SignedOut(url, SignOutReason.UserRequest))
    }

    @Test
    fun `sign-out clears the session even when the server cannot be reached`() = runBlocking {
        store.save(testSession(url))
        server.close()

        repository.signOut(current())

        assertThat(store.state.value).isEqualTo(SessionState.SignedOut(url, SignOutReason.UserRequest))
    }

    @Test
    fun `sign-out clears the session even when the server answers an error`() = runBlocking {
        store.save(testSession(url))
        server.enqueueJson("""{"statusCode":500}""", code = 500)

        repository.signOut(current())

        assertThat(store.state.value).isEqualTo(SessionState.SignedOut(url, SignOutReason.UserRequest))
    }

    @Test
    fun `sign-out has cleared the session by the time the server hears about it`() = runBlocking {
        store.save(testSession(url))
        var stateSeenByServer: SessionState? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                stateSeenByServer = store.state.value
                return MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = "{}")
            }
        }

        repository.signOut(current())

        assertThat(stateSeenByServer).isEqualTo(SessionState.SignedOut(url, SignOutReason.UserRequest))
        val request = server.takeRequest()
        assertThat(request.pathText()).isEqualTo("/api/v1/auth/logout")
        assertThat(field(request.bodyText(), "refreshToken")).isEqualTo("1".repeat(64))
    }

    @Test
    fun `sign-out clears the session even when telling the server fails unexpectedly`() = runBlocking {
        // An address Retrofit refuses: the logout call throws something that is neither a
        // network nor an HTTP error.
        store.save(testSession("not a url"))

        repository.signOut(current())

        assertThat(store.state.value).isEqualTo(SessionState.SignedOut("not a url", SignOutReason.UserRequest))
    }

    @Test
    fun `sign-out still tells the server when the caller is cancelled`() = runBlocking {
        store.save(testSession(url))
        val reached = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                reached.complete(Unit)
                release.await()
                return MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = "{}")
            }
        }
        var finished = false
        val signOut = launch(Dispatchers.IO) {
            repository.signOut(current())
            finished = true
        }

        withTimeout(TimeoutMillis) { reached.await() }
        signOut.cancel()
        release.countDown()
        withTimeout(TimeoutMillis) { signOut.join() }

        // The call was not dropped: it ran to its answer although its caller was cancelled.
        assertThat(finished).isTrue()
        assertThat(store.state.value).isEqualTo(SessionState.SignedOut(url, SignOutReason.UserRequest))
    }

    /** The profile switch tears the screen down as soon as the profile is gone: the server is told all the same. */
    @Test
    fun `sign-out still tells the server when the caller is cancelled as the profile is removed`() = runBlocking {
        store.save(testSession(url))
        server.enqueueJson("{}")
        val asked = current()
        val signOut = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { repository.signOut(asked) }
        store.afterClear = {
            signOut.cancel()
            // The registry's write coming back to a caller cancelled meanwhile.
            yield()
        }

        signOut.start()
        withTimeout(TimeoutMillis) { signOut.join() }

        assertThat(store.state.value).isEqualTo(SessionState.SignedOut(url, SignOutReason.UserRequest))
        val request = checkNotNull(server.takeRequest(TimeoutMillis, TimeUnit.MILLISECONDS)) { "Nothing was sent" }
        assertThat(request.pathText()).isEqualTo("/api/v1/auth/logout")
    }

    @Test
    fun `a second sign-out of the same profile leaves the next one signed in, and the server is told once`() =
        runBlocking {
            val other = otherAccount()
            store.save(other)
            store.save(testSession(url))
            server.enqueueJson("{}")
            server.enqueueJson("{}")
            val asked = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            store.beforeClear = {
                asked.send(Unit)
                release.await()
            }

            // Two taps, both about the profile that is current when they land.
            val current = current()
            val taps = List(2) { launch(Dispatchers.IO) { repository.signOut(current) } }
            withTimeout(TimeoutMillis) { repeat(2) { asked.receive() } }
            release.complete(Unit)
            withTimeout(TimeoutMillis) { taps.joinAll() }

            // The profile used before it took its place, and was nobody's to sign out of.
            assertThat(store.state.value).isEqualTo(SessionState.SignedIn(other))
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `a sign-out of a profile that is no longer the current one removes nobody and tells no server`() =
        runBlocking {
            store.save(testSession(url))
            val asked = current()
            // Another account signs in before the answer: its profile is the current one.
            val other = otherAccount()
            store.save(other)
            server.enqueueJson("{}")

            repository.signOut(asked)

            assertThat(store.state.value).isEqualTo(SessionState.SignedIn(other))
            assertThat(store.current.value?.id).isNotEqualTo(asked)
            assertThat(server.requestCount).isEqualTo(0)
            // The profile asked about is still there, asleep: signing out of it again, once it is current,
            // still works.
            store.save(testSession(url))
            repository.signOut(asked)
            assertThat(store.state.value).isEqualTo(SessionState.SignedIn(other))
            assertThat(server.requestCount).isEqualTo(1)
        }

    /** As before the kinds: a profile that sleeps is removed from the device only, and its session expires there. */
    @Test
    fun `the session of a profile removed while it slept is not sent anywhere`() = runBlocking {
        store.save(testSession(url))
        val sleeping = testSession(url).credential

        repository.endCredential(url, sleeping)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.state.value).isEqualTo(SessionState.SignedIn(testSession(url)))
    }

    @Test
    fun `a sign-out while nobody is signed in tells no server`() = runBlocking {
        store.setServerUrl(url, ServerKind.BookOrbit)

        repository.signOut("p0")

        assertThat(store.state.value).isEqualTo(SessionState.SignedOut(url, reason = null))
        assertThat(server.requestCount).isEqualTo(0)
    }

    // --- the download permission

    private fun withoutPermission(fixture: String) =
        Fixtures.read(fixture).replace("\"permissions\":[\"library_download\"]", "\"permissions\":[]")

    @Test
    fun `sign-in with the download permission may download`() = runBlocking {
        server.enqueueFixture("native-auth.json")

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(store.session!!.user.canDownload).isTrue()
    }

    @Test
    fun `sign-in without the download permission may not download`() = runBlocking {
        server.enqueueJson(withoutPermission("native-auth.json"))

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(store.session!!.user.canDownload).isFalse()
    }

    @Test
    fun `a superuser may download whatever the permissions say`() = runBlocking {
        server.enqueueJson(
            withoutPermission("native-auth.json").replace("\"isSuperuser\":false", "\"isSuperuser\":true"),
        )

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(store.session!!.user.canDownload).isTrue()
    }

    @Test
    fun `oidc sign-in without the download permission may not download`() = runBlocking {
        pendingOidc.pending = pending()
        server.enqueueJson(withoutPermission("native-auth.json"))

        repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=state-1")

        assertThat(store.session!!.user.canDownload).isFalse()
    }

    private companion object {
        const val TimeoutMillis = 10_000L
    }
}
