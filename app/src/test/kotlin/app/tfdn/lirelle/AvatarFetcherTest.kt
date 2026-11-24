package app.tfdn.lirelle

import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.network.AccountPictures
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.testing.bookOrbitSession
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Me = "p1"
private const val FirstPicture = "/api/v1/users/1/avatar?v=1"
private const val SecondPicture = "/api/v1/users/1/avatar?v=2"
private const val TooLarge = 2 * 1024 * 1024 + 1
private const val ServerError = 500
private const val Found = 302

/** A profile's session in memory: the test changes the account, the tokens and the mark as the store would. */
private class TestProfileSession(initial: Session) : ProfileSession {
    override val profileId = Me
    override val kind = ServerKind.BookOrbit
    override val session = MutableStateFlow(initial)
    override val signInNeeded = MutableStateFlow(false)
    override var open = true

    override suspend fun updateCredential(credential: Credential, replacing: Credential) =
        session.update { it.copy(credential = credential) }

    override suspend fun updateUser(user: UserProfile) = session.update { it.copy(user = user) }

    override suspend fun expire(refused: Credential) {
        signInNeeded.value = true
    }
}

/**
 * The picture of the account is fetched from the profile's server when it is not the one kept, and only then.
 * Robolectric only for the wait of what the fetcher does by itself once started.
 */
@RunWith(RobolectricTestRunner::class)
class AvatarFetcherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val life = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val device = MutableStateFlow(NetworkState(online = true, unmetered = true))
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars")) }
    private lateinit var session: TestProfileSession
    private lateinit var fetcher: AvatarFetcher

    @Before
    fun setUp() {
        server.start()
        elsewhere.start()
        session = TestProfileSession(session(avatarUrl = FirstPicture))
        fetcher = newFetcher()
    }

    /** Where the source says an account's picture is: here, its path put after the profile's address. */
    private var pictures = AccountPictures { server, avatarUrl -> "$server$avatarUrl".toHttpUrlOrNull() }

    /** A fetcher over the profile's network as it is built now: its states start from the session's mark. */
    private fun newFetcher() = AvatarFetcher(
        session,
        ProfileNetwork(device, emptyFlow(), session.signInNeeded, life),
        OkHttpClient(),
        avatars,
        { server, avatarUrl -> pictures.pictureUrl(server, avatarUrl) },
    )

    @After
    fun tearDown() {
        life.cancel()
        server.close()
        elsewhere.close()
    }

    private val address get() = server.url("/").toString().trimEnd('/')

    private fun session(avatarUrl: String?, access: String = "access-1") = bookOrbitSession(
        serverUrl = address,
        sessionId = 1,
        tokens = Tokens(access, Instant.MAX, "refresh-1", Instant.MAX),
        user = UserProfile("1", "reader", "Ann Reader", email = null, avatarUrl = avatarUrl),
    )

    private fun picture(vararg bytes: Byte, type: String = "image/jpeg") = MockResponse.Builder()
        .addHeader("Content-Type", type)
        .body(Buffer().write(bytes))
        .build()

    private suspend fun follow(avatarUrl: String?, online: Boolean = true) =
        fetcher.follow(session(avatarUrl), online)

    private suspend fun keptBytes() = avatars.picture(Me).first()?.readBytes()?.toList()

    @Test
    fun `the picture is fetched from the profile's server and kept`(): Unit = runBlocking {
        server.enqueue(picture(1, 2, 3))

        follow(FirstPicture)

        assertThat(keptBytes()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(avatars.keptFor(Me)).isEqualTo(FirstPicture)
        assertThat(server.takeRequest().url).isEqualTo(server.url(FirstPicture))
    }

    @Test
    fun `a picture is fetched once for its url, and again when the url changes`(): Unit = runBlocking {
        server.enqueue(picture(1))
        server.enqueue(picture(2))

        follow(FirstPicture)
        follow(FirstPicture)
        assertThat(server.requestCount).isEqualTo(1)
        follow(SecondPicture)

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(keptBytes()).containsExactly(2.toByte())
        assertThat(avatars.keptFor(Me)).isEqualTo(SecondPicture)
    }

    @Test
    fun `an account that has no picture any more loses the one kept, with or without a network`(): Unit = runBlocking {
        server.enqueue(picture(1))
        follow(FirstPicture)

        follow(avatarUrl = null, online = false)

        assertThat(keptBytes()).isNull()
        assertThat(avatars.keptFor(Me)).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a fetch that fails keeps the picture there was, and is tried again`(): Unit = runBlocking {
        server.enqueue(picture(1))
        server.enqueue(MockResponse(code = ServerError))
        server.enqueue(picture(2))
        follow(FirstPicture)

        follow(SecondPicture)

        assertThat(keptBytes()).containsExactly(1.toByte())
        assertThat(avatars.keptFor(Me)).isEqualTo(FirstPicture)

        follow(SecondPicture)

        assertThat(keptBytes()).containsExactly(2.toByte())
    }

    @Test
    fun `a server that cannot be reached is a failure like another`(): Unit = runBlocking {
        server.enqueue(picture(1))
        follow(FirstPicture)
        server.close()

        follow(SecondPicture)

        assertThat(keptBytes()).containsExactly(1.toByte())
    }

    @Test
    fun `nothing is fetched without a network`(): Unit = runBlocking {
        follow(FirstPicture, online = false)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(keptBytes()).isNull()
    }

    @Test
    fun `nothing is asked of a server that has just refused the profile, whatever the caller knew`(): Unit =
        runBlocking {
            // The mark is up, and the state the caller read has not followed yet: it still says online.
            session.signInNeeded.value = true

            follow(FirstPicture, online = true)

            assertThat(server.requestCount).isEqualTo(0)
            assertThat(keptBytes()).isNull()
        }

    @Test
    fun `nothing is fetched for a profile that is gone`(): Unit = runBlocking {
        session.open = false

        follow(FirstPicture)

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the picture is fetched where the profile's source says it is`(): Unit = runBlocking {
        pictures = AccountPictures { _, avatarUrl -> server.url("/pictures/$avatarUrl") }
        server.enqueue(picture(7))

        follow("ann")

        assertThat(server.takeRequest().url).isEqualTo(server.url("/pictures/ann"))
        assertThat(keptBytes()).containsExactly(7.toByte())
        assertThat(avatars.keptFor(Me)).isEqualTo("ann")
    }

    // Which pictures are paths on the server is the source's to say (BookOrbitAccountPicturesTest).
    @Test
    fun `a picture the source gives no address for is not fetched, nor asked for again`(): Unit = runBlocking {
        var asked = 0
        pictures = AccountPictures { _, _ -> null.also { asked++ } }

        follow(FirstPicture)
        follow(FirstPicture)

        assertThat(asked).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(keptBytes()).isNull()
    }

    @Test
    fun `an address the source gives off the profile's own server is not fetched`(): Unit = runBlocking {
        val addresses = listOf(
            elsewhere.url("/api/v1/users/1/avatar?v=1"),
            server.url("/api/v1/users/1/avatar?v=1").newBuilder().scheme("https").build(),
            server.url("/api/v1/users/1/avatar?v=1").newBuilder().host("books.example").build(),
        )
        addresses.forEachIndexed { index, address ->
            pictures = AccountPictures { _, _ -> address }
            follow("/picture/$index")
        }

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(elsewhere.requestCount).isEqualTo(0)
        assertThat(keptBytes()).isNull()
    }

    @Test
    fun `a redirection is not followed`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(Found).addHeader("Location", elsewhere.url("/picture").toString()).build(),
        )
        elsewhere.enqueue(picture(9))

        follow(FirstPicture)

        assertThat(elsewhere.requestCount).isEqualTo(0)
        assertThat(keptBytes()).isNull()
    }

    @Test
    fun `an answer that is not an image is not kept`(): Unit = runBlocking {
        server.enqueue(picture(1, type = "text/html"))
        server.enqueue(MockResponse.Builder().body(Buffer().write(byteArrayOf(1))).build())
        server.enqueue(picture())

        follow("/picture/1")
        follow("/picture/2")
        follow("/picture/3")

        assertThat(server.requestCount).isEqualTo(3)
        assertThat(keptBytes()).isNull()
    }

    @Test
    fun `a picture of two megabytes is kept, a larger one is not, whatever its length says`(): Unit = runBlocking {
        val large = ByteArray(TooLarge)
        server.enqueue(picture(*large))
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/jpeg")
                .chunkedBody(Buffer().write(large), maxChunkSize = 64 * 1024)
                .build(),
        )
        server.enqueue(picture(*ByteArray(TooLarge - 1)))

        follow("/picture/1")
        follow("/picture/2")
        assertThat(keptBytes()).isNull()
        follow("/picture/3")

        assertThat(keptBytes()).hasSize(TooLarge - 1)
    }

    @Test
    fun `a picture that was refused is not asked for again until the account's picture changes`(): Unit =
        runBlocking {
            server.enqueue(picture(1, type = "text/html"))
            server.enqueue(picture(*ByteArray(TooLarge)))
            server.enqueue(picture(3))

            follow(FirstPicture)
            follow(FirstPicture)
            assertThat(server.requestCount).isEqualTo(1)

            follow(SecondPicture)
            follow(SecondPicture)
            assertThat(server.requestCount).isEqualTo(2)

            // Back to the first: the account's picture changed, so it is asked for once more.
            follow(FirstPicture)
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(keptBytes()).containsExactly(3.toByte())
        }

    @Test
    fun `once started it fetches, and again when the account's picture changes`(): Unit = runBlocking {
        server.enqueue(picture(1))
        server.enqueue(picture(2))

        fetcher.start(life)
        until("the first picture is kept") { avatars.keptFor(Me) == FirstPicture }
        session.session.value = session(SecondPicture)

        until("the second picture is kept") { avatars.keptFor(Me) == SecondPicture }
        assertThat(keptBytes()).containsExactly(2.toByte())
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a fetch that failed is tried again when the session changes`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = ServerError))
        server.enqueue(picture(1))
        fetcher.start(life)
        until("the picture was asked for") { server.requestCount == 1 }

        session.session.value = session(FirstPicture, access = "access-2")

        until("the picture is kept") { avatars.keptFor(Me) == FirstPicture }
    }

    @Test
    fun `nothing is fetched while the profile needs sign-in, and it is when the mark falls`(): Unit = runBlocking {
        server.enqueue(picture(1))
        avatars.keep(Me, "/an/older/picture", byteArrayOf(9))
        session.signInNeeded.value = true
        // Built once the mark is up: a network built before would say online for a few dispatches more.
        fetcher = newFetcher()
        fetcher.start(life)
        // The account is followed meanwhile, which a picture that goes shows, and nothing is asked.
        session.session.value = session(avatarUrl = null)
        until("the fetcher has followed the account") { avatars.picture(Me).first() == null }
        session.session.value = session(FirstPicture)
        assertThat(server.requestCount).isEqualTo(0)

        session.signInNeeded.value = false

        until("the picture is kept") { avatars.keptFor(Me) == FirstPicture }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `the network coming back fetches what could not be`(): Unit = runBlocking {
        server.enqueue(picture(1))
        device.value = NetworkState(online = false, unmetered = false)
        fetcher.start(life)
        assertThat(server.requestCount).isEqualTo(0)

        device.value = NetworkState(online = true, unmetered = true)

        until("the picture is kept") { avatars.keptFor(Me) == FirstPicture }
    }

    @Test
    fun `it stops with the profile's scope`(): Unit = runBlocking {
        server.enqueue(picture(1))
        fetcher.start(life)
        until("the first picture is kept") { avatars.keptFor(Me) == FirstPicture }

        life.cancel()
        life.coroutineContext.job.join()
        session.session.value = session(SecondPicture)
        session.session.value = session(avatarUrl = null)

        assertThat(life.coroutineContext.job.children.toList()).isEmpty()
        assertThat(avatars.keptFor(Me)).isEqualTo(FirstPicture)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
