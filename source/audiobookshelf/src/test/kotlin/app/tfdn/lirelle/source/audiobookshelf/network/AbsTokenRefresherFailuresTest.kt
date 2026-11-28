package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.source.audiobookshelf.testing.RefresherRig
import app.tfdn.lirelle.source.audiobookshelf.testing.RenewedAccess
import app.tfdn.lirelle.source.audiobookshelf.testing.RenewedRefresh
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueRefusal
import app.tfdn.lirelle.source.audiobookshelf.testing.jsonAnswer
import app.tfdn.lirelle.source.audiobookshelf.testing.lostAnswer
import app.tfdn.lirelle.source.audiobookshelf.testing.refreshJson
import app.tfdn.lirelle.source.audiobookshelf.testing.tokens
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Long enough for the test to cancel the caller while the server holds the answer. */
private const val ResponseDelayMillis = 500L

/**
 * Audiobookshelf's renewal when it fails, and when the profile ends under it. An answer that may have been lost
 * after the server had the request — it may have rotated the refresh token — is asked again once, at once, with the
 * same token: from 2.35 the server answers that replay within its grace. Nothing more: renewals count against the
 * server's login limit. A connection never made rotated nothing, and is not tried again.
 */
class AbsTokenRefresherFailuresTest {

    private val server = MockWebServer()
    private lateinit var rig: RefresherRig
    private val profile get() = rig.profile
    private val refresher get() = rig.refresher

    @Before
    fun setUp() {
        server.start()
        rig = RefresherRig(server)
        rig.signIn(accessExpiresAt = SignedInAt)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun failsAsUnreachable() =
        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }

    @Test
    fun `an answer lost after the server had the request is asked again once with the same token`() = runBlocking {
        server.enqueue(lostAnswer())
        server.enqueueJson(refreshJson())

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo(RenewedAccess)

        assertThat(profile.session.value.tokens.refreshToken).isEqualTo(RenewedRefresh)
        assertThat(server.takeRequest().headers["x-refresh-token"]).isEqualTo("refresh-1")
        assertThat(server.takeRequest().headers["x-refresh-token"]).isEqualTo("refresh-1")
    }

    @Test
    fun `two lost answers fail, and the session is kept`() {
        repeat(2) { server.enqueue(lostAnswer()) }
        server.enqueueJson(refreshJson())

        failsAsUnreachable()

        assertThat(rig.attempts.get()).isEqualTo(2)
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
        assertThat(profile.expired).isFalse()
    }

    @Test
    fun `a server error is tried again once, then keeps the session and fails`() {
        repeat(3) { server.enqueueJson("""{"error":"failed"}""", code = 500) }

        failsAsUnreachable()

        assertThat(rig.attempts.get()).isEqualTo(2)
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
    }

    @Test
    fun `an unreadable answer is tried again once, then keeps the session and fails`() {
        repeat(3) {
            server.enqueue(
                MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>Sign in to the proxy</html>")
                    .build(),
            )
        }

        failsAsUnreachable()

        assertThat(rig.attempts.get()).isEqualTo(2)
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
    }

    @Test
    fun `an answer without an access token is unreadable`() {
        repeat(3) { server.enqueueJson(refreshJson(accessToken = null)) }

        failsAsUnreachable()

        assertThat(rig.attempts.get()).isEqualTo(2)
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
    }

    @Test
    fun `a connection that could not be made rotated nothing and is not tried again`() {
        server.close()

        failsAsUnreachable()

        assertThat(rig.attempts.get()).isEqualTo(1)
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
    }

    @Test
    fun `a 401 on the second try still marks the profile`() {
        server.enqueue(lostAnswer())
        server.enqueueRefusal()

        failsAsUnreachable()

        assertThat(profile.expired).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a caller cancelled during the renewal still stores the renewed pair`() = runBlocking {
        server.enqueue(jsonAnswer(refreshJson()).headersDelay(ResponseDelayMillis, TimeUnit.MILLISECONDS).build())

        val caller = launch(Dispatchers.Default) { refresher.freshTokens() }
        // The server has the request, so it has rotated the refresh token.
        server.takeRequest()
        caller.cancelAndJoin()

        assertThat(profile.session.value.tokens.accessToken).isEqualTo(RenewedAccess)
        assertThat(profile.session.value.tokens.refreshToken).isEqualTo(RenewedRefresh)
    }

    /** A screen that is leaving, a service's last write: served while the tokens it has still do. */
    @Test
    fun `a request begun after close goes out with the tokens already known`() = runBlocking {
        rig.signIn()
        profile.gone = true

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** The sign-out is sending that refresh token to the server to end the session: it is not replayed beside it. */
    @Test
    fun `a request begun after close renews nothing and fails`() {
        val before = profile.session.value
        server.enqueueJson(refreshJson())
        profile.gone = true

        failsAsUnreachable()
        assertThrows(IOException::class.java) {
            runBlocking { refresher.freshTokens(rejectedAccessToken = "access-1") }
        }

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(profile.session.value).isEqualTo(before)
        assertThat(profile.expired).isFalse()
    }

    @Test
    fun `a renewal in flight at close writes nowhere`() = runBlocking<Unit> {
        val before = profile.session.value
        val answer = rig.held(jsonAnswer(refreshJson()))

        val caller = async(Dispatchers.Default) { refresher.freshTokens() }
        server.takeRequest()
        profile.gone = true
        answer.countDown()

        // The caller's own request may still go out with them; nothing keeps them.
        assertThat(caller.await()?.accessToken).isEqualTo(RenewedAccess)
        assertThat(profile.session.value).isEqualTo(before)
    }

    @Test
    fun `an answer lost for a profile that closed meanwhile is not asked again`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                profile.gone = true
                return lostAnswer()
            }
        }

        failsAsUnreachable()

        assertThat(server.requestCount).isEqualTo(1)
    }
}
