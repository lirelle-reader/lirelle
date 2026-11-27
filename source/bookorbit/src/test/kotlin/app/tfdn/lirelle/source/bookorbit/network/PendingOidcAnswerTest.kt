package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.source.bookorbit.network.testing.InMemoryPendingOidcStore
import app.tfdn.lirelle.source.bookorbit.network.testing.InMemorySessionStore
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueFixture
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.testing.tokens
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Answer = "bookorbit://oauth2-callback?code=code-1&state=state-1"
private const val Crafted = "bookorbit://oauth2-callback?code=code-1&state=other"

/**
 * Which redirects end the OIDC sign-in that waits: only its own answer, and only for a while. Any app can send
 * the redirect link, so one that does not carry the state of the sign-in begun leaves it waiting; and a
 * sign-in begun long ago is refused, so that a browser tab finished much later signs nobody in.
 */
class PendingOidcAnswerTest {

    private val server = MockWebServer()
    private val store = InMemorySessionStore()
    private val pendingOidc = InMemoryPendingOidcStore()
    private var now = Instant.parse("2026-11-21T10:00:00Z")
    private lateinit var repository: BookOrbitSignIn
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        repository = BookOrbitSignIn(
            store = store,
            pendingOidc = pendingOidc,
            publicApi = ApiProvider(OkHttpClient(), ApiJson, PublicApi::class.java),
            errors = ErrorMapper(ApiJson),
            deviceLabel = { "Phone (Lirelle)" },
            now = { now },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** A sign-in begun [ago], waiting for the redirect that carries `state-1`. */
    private fun begun(ago: Duration? = Duration.ZERO) = PendingOidcRequest(
        serverUrl = url,
        providerSlug = "authentik",
        state = "state-1",
        codeVerifier = "verifier-1",
        nonce = "nonce-1",
        begunAtMillis = ago?.let { now.minus(it).toEpochMilli() },
    )

    @Test
    fun `a redirect that is not the answer of the sign-in that waits leaves it waiting`() = runBlocking {
        pendingOidc.pending = begun()

        assertThat(repository.completeOidc(Crafted)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(repository.completeOidc("bookorbit://oauth2-callback?error=access_denied&state=other"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(repository.completeOidc("bookorbit://oauth2-callback?code=code-1"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))

        assertThat(pendingOidc.pending).isEqualTo(begun())
        assertThat(server.requestCount).isEqualTo(0)
        // Its own answer still completes it.
        server.enqueueFixture("native-auth.json")
        assertThat(repository.completeOidc(Answer)).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.session?.tokens?.accessToken).isEqualTo("access-1")
    }

    @Test
    fun `the provider's refusal of the sign-in that waits ends it`() = runBlocking {
        pendingOidc.pending = begun()

        assertThat(repository.completeOidc("bookorbit://oauth2-callback?error=access_denied&state=state-1"))
            .isEqualTo(ApiResult.Failure(ApiError.OidcRefused))

        assertThat(pendingOidc.pending).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a redirect answers the sign-in that waits when it carries its state`() = runBlocking {
        assertThat(repository.answersPendingOidc(Answer)).isFalse()
        pendingOidc.pending = begun()

        assertThat(repository.answersPendingOidc(Answer)).isTrue()
        assertThat(repository.answersPendingOidc("bookorbit://oauth2-callback?error=access_denied&state=state-1"))
            .isTrue()
        assertThat(repository.answersPendingOidc(Crafted)).isFalse()
        assertThat(repository.answersPendingOidc("bookorbit://oauth2-callback?code=code-1")).isFalse()
        assertThat(repository.answersPendingOidc("https://evil.example.com/?code=code-1&state=state-1")).isFalse()
        // Asking uses nothing up.
        assertThat(pendingOidc.pending).isEqualTo(begun())
    }

    /** Audiobookshelf's sign-in waits in the same place, and returns to the address its record names. */
    @Test
    fun `an Audiobookshelf return is not BookOrbit's, even with the state of the sign-in that waits`() =
        runBlocking {
            val audiobookshelf = begun().copy(redirectUri = "lirelle://oauth", cookies = "connect.sid=abc")
            pendingOidc.pending = audiobookshelf

            listOf("lirelle://oauth?code=code-1&state=state-1", "audiobookshelf://oauth?code=code-1&state=state-1")
                .forEach {
                    assertThat(repository.answersPendingOidc(it)).isFalse()
                    assertThat(repository.completeOidc(it)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
                }

            assertThat(pendingOidc.pending).isEqualTo(audiobookshelf)
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test
    fun `a sign-in is begun at the time it is`() = runBlocking {
        server.enqueueFixture("oidc-state.json")

        repository.beginOidc(url, OidcProvider("authentik", "Authentik", "client-id-1", "openid"))

        assertThat(pendingOidc.pending?.begunAtMillis).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun `a sign-in begun a few minutes ago is completed`() = runBlocking {
        pendingOidc.pending = begun(ago = Duration.ofMinutes(9))
        server.enqueueFixture("native-auth.json")

        assertThat(repository.completeOidc(Answer)).isEqualTo(ApiResult.Success(Unit))
    }

    @Test
    fun `a sign-in begun too long ago is refused, and waits no more`() = runBlocking {
        pendingOidc.pending = begun(ago = Duration.ofMinutes(11))

        assertThat(repository.completeOidc(Answer)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))

        assertThat(pendingOidc.pending).isNull()
        assertThat(store.session).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** The clock was set back meanwhile, by more than a sign-in lasts: nothing says how old it is. */
    @Test
    fun `a sign-in begun long after now is refused`() = runBlocking {
        pendingOidc.pending = begun(ago = Duration.ofMinutes(-11))

        assertThat(repository.completeOidc(Answer)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** Stored by a version that did not keep the time. */
    @Test
    fun `a sign-in that does not say when it was begun is refused`() = runBlocking {
        pendingOidc.pending = begun(ago = null)

        assertThat(repository.completeOidc(Answer)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))

        assertThat(pendingOidc.pending).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }
}
