package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.isOnServer
import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.copyCredential
import app.tfdn.lirelle.core.testing.tokens
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val Length = 5_000
private val Book = BookId("1")
private const val Refused = """{"statusCode":401,"message":"Unauthorized"}"""

/** A profile's session in memory: marked when the server refuses to renew it, until somebody signs in again. */
private class MemorySession(serverUrl: String) : ProfileSession {
    override val profileId = "p1"
    override val kind = ServerKind.BookOrbit
    override val session = MutableStateFlow(
        bookOrbitSession(serverUrl, sessionId = 1, tokens("dead"), UserProfile("1", "reader", "Reader", email = null)),
    )
    override val signInNeeded = MutableStateFlow(false)
    override val open = true

    override suspend fun updateCredential(credential: Credential, replacing: Credential) =
        session.update { it.copy(credential = credential) }

    override suspend fun updateUser(user: UserProfile) = Unit

    override suspend fun expire(refused: Credential) {
        signInNeeded.value = true
    }

    /** As the same account signing in again leaves it: the new tokens first, then the mark falls. */
    fun signInAgain() {
        session.update { it.copyCredential(tokens = tokens("again")) }
        signInNeeded.value = false
    }

    private companion object {
        fun tokens(name: String) = Tokens("access-$name", Instant.MAX, "refresh-$name", Instant.MAX)
    }
}

/**
 * A profile's source whose server refuses to renew the session, as the authorizer's contract says one behaves
 * then: the tokens held are given until the server refuses them; the refused token is renewed into nothing, the
 * profile is marked, and every request fails as without a network until the same account signs in again. It
 * counts the renewals it was asked for.
 */
private class EndedSessionAuthorizer(private val session: MemorySession) : SessionAuthorizer {
    val renewals = AtomicInteger()

    override suspend fun header(target: HttpUrl): AuthHeader? = answer(rejectedAccessToken = null, target)

    override suspend fun rejected(sent: String, target: HttpUrl): AuthHeader? =
        answer(rejectedAccessToken = sent.removePrefix("Bearer "), target)

    private suspend fun answer(rejectedAccessToken: String?, target: HttpUrl): AuthHeader? {
        val known = session.session.value
        if (!target.isOnServer(known.serverUrl)) return null
        if (session.signInNeeded.value) throw IOException("The session needs a sign-in")
        val accessToken = known.tokens.accessToken
        if (rejectedAccessToken != accessToken) return AuthHeader("Authorization", "Bearer $accessToken")
        renewals.incrementAndGet()
        session.expire(refused = known.credential)
        throw IOException("The server refused to renew the session")
    }
}

/**
 * The files come from a server through the real downloader and the client of a profile, which adds the access
 * token and has the profile's source renew it: what a download makes of a session that the server ends in the
 * middle of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredSessionDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val content = bytes(Length)
    private lateinit var session: MemorySession
    private lateinit var authorizer: EndedSessionAuthorizer

    /** The file's route: only the tokens of the new sign-in are taken. */
    private val endedSession = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val taken = request.headers["Authorization"] == "Bearer access-again"
            val answer = if (taken) whole(content) else MockResponse.Builder().code(Unauthorized).body(Refused)
            return answer.build()
        }
    }

    @Before
    fun start() {
        server.start()
        server.dispatcher = endedSession
        session = MemorySession(server.url("/").toString().removeSuffix("/"))
        authorizer = EndedSessionAuthorizer(session)
    }

    @After
    fun stop() = server.close()

    private fun httpTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        val public = OkHttpClient()
        val fixture = QueueFixture(this, folder.newFolder("downloads"), fetch = { places, io ->
            val client = newAuthenticatedClient(public, authorizer)
            Downloader(client, places, testLocations(session.session.value.serverUrl), TestRefusals(), io)
        })
        fixture.test(this)
    }

    private fun QueueFixture.serveAudiobook() {
        val asset = FileSource.AudioAsset("a1")
        val file = PlannedFile("a1", asset, "v1", Length.toLong(), resumable = true, kind = FileKind.Audio)
        serve(Book, Audiobook, file)
    }

    /** Every request the server had so far, in order: its path and the token it came with. */
    private fun requests(): List<Pair<String, String?>> =
        List(server.requestCount) { server.takeRequest() }.map { it.url.encodedPath to it.headers["Authorization"] }

    @Test
    fun `a file that meets the end of the session waits as without a network, and is not failed`() = httpTest {
        serveAudiobook()
        queue.ask(Book, setOf(Audiobook))
        keepRunning()

        it.runCurrent()

        // Failed is for good: the queue never tries it again. Waiting is tried again.
        assertThat((state(Book, Audiobook) as? FormatState.Waiting)?.reason).isEqualTo(WaitReason.NoNetwork)
        assertThat(session.signInNeeded.value).isTrue()
        // The file with the token the server ended, one renewal, refused, and nothing without a token.
        assertThat(requests()).containsExactly("/audio/1/a1" to "Bearer access-dead")
        assertThat(authorizer.renewals.get()).isEqualTo(1)
    }

    @Test
    fun `the download goes on by itself once the same account has signed in again`() = httpTest {
        serveAudiobook()
        queue.ask(Book, setOf(Audiobook))
        keepRunning()
        it.runCurrent()

        session.signInAgain()
        it.advanceTimeBy(FirstBackOffMs)
        it.runCurrent()

        assertThat(state(Book, Audiobook)).isEqualTo(FormatState.Complete(Length.toLong()))
        assertThat(file(Book, Audiobook, "a1").readBytes()).isEqualTo(content)
    }

    private companion object {
        const val Unauthorized = 401

        /** How long the queue waits after a first cut. */
        const val FirstBackOffMs = 5_000L
    }
}
