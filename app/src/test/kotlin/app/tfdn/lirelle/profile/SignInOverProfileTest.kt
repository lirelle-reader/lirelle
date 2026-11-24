package app.tfdn.lirelle.profile

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.ProfileGraph
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.core.testing.tokens
import app.tfdn.lirelle.feature.login.ServerViewModel
import app.tfdn.lirelle.feature.login.SignInOverProfileViewModel
import app.tfdn.lirelle.feature.login.SignInPurpose
import app.tfdn.lirelle.feature.login.SignInViewModel
import app.tfdn.lirelle.feature.login.StrayRedirectsViewModel
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Reader = 5L
private const val Other = 6L
private const val Unavailable = 503

/** The access token the sign-in server gives the account [userId], unlike the one a test signs it in with. */
private fun accessFromServer(userId: Long) = "access-from-server-$userId"

/**
 * A server one can sign in to with a password: it says it is a BookOrbit server, offers the password, and
 * opens a session for whoever asks — the account `user<N>` is the account N — and prepares an OIDC sign-in,
 * which it never completes. Unavailable for everything else, as the profiles' own requests find it.
 */
private class SignInServer : Closeable {
    private val server = MockWebServer()
    private val asked = AtomicInteger()

    /** How many times an OIDC sign-in was brought here to be completed. */
    val callbacks: Int get() = asked.get()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/server-identity" -> json("""{"product":"bookorbit","protocolVersion":1,"instanceId":"i"}""")
                "/api/v1/auth/login-options" ->
                    json("""{"passwordLoginEnabled":true,"allowRegistration":false,"oidcProviders":[]}""")
                "/api/v1/auth/login" -> json(sessionOf(request.body?.utf8().orEmpty()))
                "/api/v1/auth/oidc/callback" -> MockResponse(code = Unavailable).also { asked.incrementAndGet() }
                "/api/v1/auth/oidc/authentik/state" ->
                    json("""{"state":"state-1","authorizationEndpoint":"https://auth.example.com/authorize/"}""")
                else -> MockResponse(code = Unavailable)
            }
        }
        server.start()
    }

    /** As the app stores a server's address: no trailing slash. */
    val address: String get() = server.url("/").toString().trimEnd('/')

    private fun json(body: String) =
        MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)

    private fun sessionOf(login: String): String {
        val userId = checkNotNull(Regex(""""username":"user(\d+)"""").find(login)).groupValues[1].toLong()
        val user = """{"id":$userId,"username":"user$userId","name":"User $userId","email":null,"active":true,""" +
            """"isSuperuser":false,"isDefaultPassword":false,"settings":{"timezone":"Europe/Paris"},""" +
            """"avatarUrl":null,"provisioningMethod":"local","authenticationMethod":"password","permissions":[]}"""
        return """{"accessToken":"${accessFromServer(userId)}","accessTokenExpiresAt":"2099-01-01T00:00:00.000Z",""" +
            """"refreshToken":"refresh-from-server-$userId","refreshTokenExpiresAt":"2099-01-01T00:00:00.000Z",""" +
            """"sessionId":${userId + 100},"user":$user}"""
    }

    override fun close() = server.close()
}

/** The device's sign-in, noting each time it is asked whether a redirect answers the OIDC sign-in pending. */
private class WatchedAuth(private val real: AuthRepository) : AuthRepository by real {
    private val asked = AtomicInteger()

    val pendingAsked: Int get() = asked.get()

    override suspend fun answersPendingOidc(redirectUri: String): Boolean =
        real.answersPendingOidc(redirectUri).also { asked.incrementAndGet() }
}

/**
 * Signing in while a profile is open, on the application's real graph and registry, with the sign-in view
 * models as the screens shown over a profile make them: going back changes nothing but the address typed, and
 * a redirect that comes after it signs nobody in; the
 * current profile signing in again keeps its objects and loses its mark; another account gets its own profile,
 * which becomes the current one.
 */
@RunWith(RobolectricTestRunner::class)
class SignInOverProfileTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val server = SignInServer()
    private val otherServer = SignInServer()
    private val store get() = device.data.profileStore()
    private val auth get() = device.data.auth()

    @After
    fun close(): Unit = runBlocking {
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed and dropped") {
            device.data.openProfiles().current.value == null && device.listed()?.isEmpty() == true
        }
        server.close()
        otherServer.close()
    }

    private fun session(userId: Long) = bookOrbitSession(
        serverUrl = server.address,
        sessionId = userId,
        tokens = Tokens("access-$userId", Instant.MAX, "refresh-$userId", Instant.MAX),
        user = UserProfile("$userId", "user$userId", "User $userId", email = null),
    )

    private fun shownUser(): String? =
        (device.data.openProfiles().shown.value.state as? SessionState.SignedIn)?.session?.user?.id

    /** The reader is signed in, as at a first sign-in. @return the profile shown from then on. */
    private suspend fun readerSignedIn(): ProfileGraph {
        device.data.sessions().save(session(Reader))
        until("the reader's profile is shown") { shownUser() == "$Reader" }
        return checkNotNull(device.data.openProfiles().current.value)
    }

    /** The registry as a test compares it: who is listed, in which order, with which session and mark. */
    private fun registry(): List<Profile> = store.profiles.value

    /** The sign-in step on [server], or on the remembered one, once it offers the password. */
    private suspend fun signInStep(server: String? = null): SignInViewModel {
        val viewModel =
            SignInViewModel(auth, device.data.oidcRedirects(), server, ServerKind.BookOrbit.takeIf { server != null })
        until("the server's sign-in methods are offered") { viewModel.state.value.options != null }
        return viewModel
    }

    /** Signs [userId] in with its password, and returns once the step has told that it succeeded. */
    private suspend fun SignInViewModel.signsIn(userId: Long) = coroutineScope {
        val told = async(start = CoroutineStart.UNDISPATCHED) { signedIn.first() }
        onUsernameChange("user$userId")
        onPasswordChange("secret")
        onSignIn()
        until("the sign-in of the account $userId has succeeded") { told.isCompleted }
    }

    /** What [screens] were told to do by themselves until now: whether they were to leave. Never waits. */
    private fun CoroutineScope.leftByItself(screens: SignInOverProfileViewModel): Deferred<Unit> =
        async(start = CoroutineStart.UNDISPATCHED) { screens.left.first() }

    @Test
    fun `adding a profile then going back changes nothing of the registry, and keeps the address typed`(): Unit =
        runBlocking {
            val open = readerSignedIn()
            val before = registry()
            val watched = WatchedAuth(auth)
            val stray = StrayRedirectsViewModel(watched, device.data.oidcRedirects())
            stray.follow(signInShown = true)

            // The server step offers the address used last, and the user types another.
            val serverStep = ServerViewModel(auth)
            assertThat(serverStep.state.value.address).isEqualTo(server.address)
            serverStep.onAddressChange(otherServer.address)
            serverStep.onContinue()
            serverStep.onCleartextConfirmed()
            until("the address typed is checked and remembered") { auth.serverUrl.value == otherServer.address }
            val step = signInStep()
            assertThat(step.state.value.serverUrl).isEqualTo(otherServer.address)
            // An OIDC sign-in is started, the browser is in front…
            step.onOidc(OidcProvider("authentik", "Authentik", "client-id-1", "openid"))
            until("the OIDC sign-in waits for its redirect") { auth.hasPendingOidc() }

            // …and the user comes back and leaves the screens: Back, which clears their view models. The
            // redirect comes after.
            step.viewModelScope.cancel()
            val afterBack = launch { stray.follow(signInShown = false) }
            until("the sign-in left pending is cancelled") { !auth.hasPendingOidc() }
            val asked = watched.pendingAsked
            device.data.oidcRedirects().deliver("bookorbit://oauth2-callback?code=code-1&state=state-1")
            until("the redirect was taken, and found nothing pending") { watched.pendingAsked > asked }
            afterBack.cancelAndJoin()

            assertThat(server.callbacks + otherServer.callbacks).isEqualTo(0)
            assertThat(registry()).isEqualTo(before)
            assertThat(store.current.value).isEqualTo(before.single())
            assertThat(device.data.openProfiles().current.value).isSameInstanceAs(open)
            assertThat(device.data.sessions().state.value).isEqualTo(SessionState.SignedIn(session(Reader)))
            assertThat(auth.serverUrl.value).isEqualTo(otherServer.address)
            assertThat(auth.hasPendingOidc()).isFalse()
        }

    @Test
    fun `the current profile signing in again loses its mark, keeps its objects, and the screens leave`(): Unit =
        runBlocking {
            val open = readerSignedIn()
            store.needSignIn(open.profileId, session(Reader).credential)
            assertThat(store.profile(open.profileId)?.signInNeeded).isTrue()
            // The address typed last is another server's: the profile signs in again on its own.
            device.data.sessions().setServerUrl(otherServer.address, ServerKind.BookOrbit)
            val screens = SignInOverProfileViewModel(store, SignInPurpose.Again(open.profileId))
            assertThat(screens.state.again?.serverUrl).isEqualTo(server.address)
            val step = signInStep(screens.state.again?.serverUrl)

            // An OIDC sign-in begun earlier, on the other server, still waits for its redirect.
            signInStep().onOidc(OidcProvider("authentik", "Authentik", "client-id-1", "openid"))
            until("the OIDC sign-in waits for its redirect") { auth.hasPendingOidc() }

            step.signsIn(Reader)
            screens.onSignedIn()

            // Whoever signs in ends it: a browser tab finished later signs nobody in.
            assertThat(auth.hasPendingOidc()).isFalse()
            // The server just signed in to is the one remembered, as after any sign-in.
            assertThat(auth.serverUrl.value).isEqualTo(server.address)
            assertThat(leftByItself(screens).isCompleted).isTrue()
            val profile = registry().single()
            assertThat(profile.id).isEqualTo(open.profileId)
            assertThat(profile.signInNeeded).isFalse()
            assertThat(profile.session.tokens.accessToken).isEqualTo(accessFromServer(Reader))
            assertThat(device.data.openProfiles().current.value).isSameInstanceAs(open)
            assertThat(device.data.openProfiles().shown.value.switching).isFalse()
        }

    @Test
    fun `adding another account makes its profile the current one, and the screens leave nothing`(): Unit =
        runBlocking {
            val open = readerSignedIn()
            val reader = registry().single()
            val screens = SignInOverProfileViewModel(store, SignInPurpose.Add)
            val step = signInStep()

            step.signsIn(Other)
            screens.onSignedIn()

            // The level is rebuilt for the new profile by the change of profile itself.
            val left = leftByItself(screens)
            assertThat(left.isCompleted).isFalse()
            left.cancelAndJoin()
            assertThat(registry().map { it.session.user.id }).containsExactly("$Other", "$Reader").inOrder()
            assertThat(registry().last()).isEqualTo(reader)
            until("the new account's profile is shown") { shownUser() == "$Other" }
            assertThat(device.data.openProfiles().current.value?.profileId).isNotEqualTo(open.profileId)
            assertThat(device.data.openProfiles().current.value?.profileId).isEqualTo(store.current.value?.id)
        }
}
