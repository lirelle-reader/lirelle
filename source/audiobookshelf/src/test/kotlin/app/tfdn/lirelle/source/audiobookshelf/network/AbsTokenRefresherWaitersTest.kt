package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.source.audiobookshelf.testing.RefresherRig
import app.tfdn.lirelle.source.audiobookshelf.testing.RenewedAccess
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueTooMany
import app.tfdn.lirelle.source.audiobookshelf.testing.refreshJson
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

private const val Callers = 5

/**
 * What a failed renewal leaves behind it: renewals count against the server's login limit (40 per 10 minutes per
 * address, shared by a household), so the callers that waited behind it get its failure without asking again, and
 * after a 429 nothing is asked for that sign-in for a minute. A new sign-in is never held back by either.
 */
class AbsTokenRefresherWaitersTest {

    private val server = MockWebServer()
    private lateinit var rig: RefresherRig
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

    /**
     * [Callers] callers, each started until it waits — the first sending the renewal, the others behind the lock —
     * before any answer comes back: all of them waited behind the first.
     */
    private fun callersAtOnce(): List<Result<*>> = runBlocking {
        List(Callers) {
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { refresher.freshTokens() } }
        }.awaitAll()
    }

    private fun failsAtOnce() = assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }

    @Test
    fun `callers behind a renewal that failed get its failure without asking again`() {
        repeat(Callers * 2) { server.enqueueJson("""{"error":"unavailable"}""", code = 503) }

        val results = callersAtOnce()

        results.forEach { assertThat(it.exceptionOrNull()).isInstanceOf(IOException::class.java) }
        // The one try, and its one retry.
        assertThat(rig.attempts.get()).isEqualTo(2)
        assertThat(rig.profile.expired).isFalse()
    }

    @Test
    fun `a caller that comes after the failure tries again`() {
        server.enqueueJson("""{"error":"unavailable"}""", code = 503)
        server.enqueueJson("""{"error":"unavailable"}""", code = 503)
        failsAtOnce()
        server.enqueueJson(refreshJson())

        assertThat(runBlocking { refresher.freshTokens() }?.accessToken).isEqualTo(RenewedAccess)
        assertThat(rig.attempts.get()).isEqualTo(3)
    }

    @Test
    fun `after too many attempts nothing is asked for that sign-in for a minute`() {
        repeat(Callers) { server.enqueueTooMany() }

        callersAtOnce().forEach { assertThat(it.exceptionOrNull()).isInstanceOf(IOException::class.java) }
        assertThat(rig.attempts.get()).isEqualTo(1)

        rig.clock = SignedInAt.plusSeconds(30)
        failsAtOnce()
        assertThat(rig.attempts.get()).isEqualTo(1)
        assertThat(rig.profile.expired).isFalse()

        // Asked again, and refused again: another 429 the server had queued.
        rig.clock = SignedInAt.plusSeconds(61)
        failsAtOnce()
        assertThat(rig.attempts.get()).isEqualTo(2)
    }

    @Test
    fun `a minute after too many attempts the renewal is asked again`() = runBlocking {
        server.enqueueTooMany()
        failsAtOnce()

        rig.clock = SignedInAt.plusSeconds(61)
        server.enqueueJson(refreshJson())

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo(RenewedAccess)
        assertThat(rig.attempts.get()).isEqualTo(2)
    }

    @Test
    fun `a sign-in made after too many attempts is renewed at once`() = runBlocking {
        server.enqueueTooMany()
        failsAtOnce()

        rig.signInAgain()
        server.enqueueJson(refreshJson())

        assertThat(refresher.freshTokens(rejectedAccessToken = "access-again")?.accessToken).isEqualTo(RenewedAccess)
        assertThat(server.takeRequest().headers["x-refresh-token"]).isEqualTo("refresh-1")
        assertThat(server.takeRequest().headers["x-refresh-token"]).isEqualTo("refresh-again")
    }

    @Test
    fun `a sign-in made while callers waited behind a failed renewal is not held back`() = runBlocking {
        server.enqueueJson("""{"error":"unavailable"}""", code = 503)
        server.enqueueJson("""{"error":"unavailable"}""", code = 503)
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { refresher.freshTokens() } }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            refresher.freshTokens(rejectedAccessToken = "access-again")
        }
        rig.signInAgain()
        server.enqueueJson(refreshJson())

        assertThat(first.await().exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(waiter.await()?.accessToken).isEqualTo(RenewedAccess)
        assertThat(rig.attempts.get()).isEqualTo(3)
    }
}
