package app.tfdn.lirelle.signout

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.testing.AndroidAutoCertificates
import app.tfdn.lirelle.core.testing.InstalledAndroidAuto
import app.tfdn.lirelle.core.testing.SignedPackages
import app.tfdn.lirelle.feature.player.car.CarCoverProvider
import app.tfdn.lirelle.feature.player.car.CarCovers
import app.tfdn.lirelle.feature.player.service.PlaybackService
import app.tfdn.lirelle.feature.player.service.PlaybackServiceRunning
import app.tfdn.lirelle.profile.RecordingServer
import app.tfdn.lirelle.profile.bearerFromServer
import app.tfdn.lirelle.profile.deviceOnline
import app.tfdn.lirelle.profile.signsInOn
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

private const val Ann = 1L
private const val Bob = 2L
private const val AnnBook = 101L
private const val BobBook = 202L

private const val Tabs = "root"
private const val Audiobooks = "tab/audiobooks"

/** What the car's library asks a server: its lists of books, and a book's small cover. */
private const val BooksPath = "/api/v1/books/query"
private val CoverPath = Regex("/api/v1/books/(\\d+)/thumbnail")

/**
 * The server of one account, which has one audiobook, [book], with a cover: whatever is asked of its lists —
 * the books to listen to, a search — it answers that book, and its cover is a picture that names it.
 */
private fun serverWith(book: Long, title: String) = RecordingServer(signsIn = true, has = { request ->
    val cover = CoverPath.matchEntire(request.url.encodedPath)?.groupValues?.get(1)
    when {
        request.url.encodedPath == BooksPath -> json(
            """{"items":[{"id":$book,"title":"$title","files":[{"id":1,"format":"mp3","role":"primary"}],""" +
                """"hasCover":true,"coverVersion":"v1"}],"total":1}""",
        )
        cover == "$book" -> MockResponse(code = 200, body = coverOf(request))
        else -> null
    }
})

/** The picture a server gives for a cover: the book asked, and the account that asked it. */
private fun coverOf(request: RecordedRequest) = "${request.url.encodedPath} for ${request.headers["Authorization"]}"

private fun json(body: String) =
    MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)

private fun LibraryResult<out List<MediaItem>>.ids(): List<String>? = value?.map { it.mediaId }

/**
 * What a car is shown is the profile open on the phone, and nothing of another: two accounts signed in on the
 * device, each on its own server with its own audiobook; the playback service as the system makes it, on the
 * application's real graph; a car at its session ([CarAtTheService]); and the covers' provider as the system
 * makes it, asked by the car's host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [SignedPackages::class])
class PlaybackServiceProfileCarTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    @get:Rule
    val androidAuto = InstalledAndroidAuto()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val anns = serverWith(AnnBook, "Ann's Road")
    private val bobs = serverWith(BobBook, "Bob's Road")
    private val made = mutableListOf<ServiceController<PlaybackService>>()
    private val authority = CarCovers.authority(context.packageName)

    /** The car's host asks for covers on threads of its own. */
    private val host = CoroutineScope(Dispatchers.IO)

    /** The provider of the covers, made as the system makes it: it finds the open profile by itself. */
    private val covers by lazy {
        Robolectric.buildContentProvider(CarCoverProvider::class.java).create(authority).get()
            .also { shadowOf(it).setCallingPackage(CarHost) }
    }

    @Before
    fun deviceOnline() = context.deviceOnline()

    @After
    fun close(): Unit = runBlocking {
        host.cancel()
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed and dropped") {
            device.data.openProfiles().current.value == null && device.listed()?.isEmpty() == true
        }
        made.forEach { it.destroy() }
        assertThat(PlaybackServiceRunning.running.value).isFalse()
        anns.close()
        bobs.close()
    }

    /** The service, created and started as the system does it for a car that connects. */
    private fun service(): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).also { made += it }.create().get()
            .also { it.onStartCommand(Intent(), 0, 1) }

    private fun Service.stopped() = shadowOf(this).isStoppedBySelf

    private fun coverUri(profileId: String, book: Long): Uri = CarCovers.uri(authority, profileId, BookId("$book"))

    /** What the car's host is served at [uri]: the picture, or the refusal. */
    private suspend fun served(uri: Uri): Result<String> {
        val asked = host.async {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(covers.openFile(uri, "r")).use { it.readBytes() }
                    .decodeToString()
            }
        }
        return awaited("the car's host is answered for $uri") { asked.takeIf { it.isCompleted }?.await() }
    }

    /** The root, its tabs, a tab's books and a search are those of the profile [profileId], whose book is [book]. */
    private suspend fun CarAtTheService.assertShows(profileId: String, book: Long) {
        val root = root()
        assertWithMessage("root").that(root.value?.mediaId).isEqualTo(Tabs)
        assertWithMessage("tabs").that(children(Tabs).ids())
            .containsExactly("tab/home", "tab/downloaded", Audiobooks, "tab/series").inOrder()
        val listed = children(Audiobooks)
        assertWithMessage("audiobooks").that(listed.ids()).containsExactly("book/$book")
        assertWithMessage("cover's address").that(listed.value?.single()?.mediaMetadata?.artworkUri)
            .isEqualTo(coverUri(profileId, book))
        assertWithMessage("search").that(search("road").ids()).containsExactly("book/$book")
    }

    @Test
    fun `the car's root, a tab, a search and a cover are those of the open profile`(): Unit = runBlocking {
        val ann = device.signsInOn(anns, Ann)

        CarAtTheService(service()).assertShows(ann.id, AnnBook)

        assertThat(served(coverUri(ann.id, AnnBook)).getOrThrow())
            .isEqualTo("/api/v1/books/$AnnBook/thumbnail for ${bearerFromServer(Ann)}")
        assertThat(served(coverUri("another-profile", AnnBook)).exceptionOrNull())
            .isInstanceOf(FileNotFoundException::class.java)
    }

    @Test
    fun `an app that took Android Auto's name is shown nothing of the open profile, and served no cover`(): Unit =
        runBlocking {
            val ann = device.signsInOn(anns, Ann)
            SignedPackages.sign(CarHost, AndroidAutoCertificates.Stranger)
            val stranger = CarAtTheService(service())
            val heard = anns.heard

            val codes =
                listOf(stranger.root(), stranger.children(Tabs), stranger.children(Audiobooks), stranger.search("road"))
                    .map { it.resultCode }

            assertThat(codes.toSet()).containsExactly(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(served(coverUri(ann.id, AnnBook)).exceptionOrNull())
                .isInstanceOf(FileNotFoundException::class.java)
            assertThat(anns.heard).isEqualTo(heard)
        }

    @Test
    fun `the profile changed - the car is shown the new one's, and nothing of the first is served`(): Unit =
        runBlocking {
            val ann = device.signsInOn(anns, Ann)
            val first = service()
            val stayed = CarAtTheService(first)
            stayed.assertShows(ann.id, AnnBook)
            assertThat(served(coverUri(ann.id, AnnBook)).isSuccess).isTrue()

            val bob = device.signsInOn(bobs, Bob)
            until("the first profile's service stops itself") { first.stopped() }
            val heardByAnns = anns.heard

            // A car that still holds the first service's session is listed nothing of the profile that closed.
            val codes = listOf(stayed.root(), stayed.children(Tabs), stayed.children(Audiobooks), stayed.search("road"))
                .map { it.resultCode }
            assertThat(codes.toSet()).containsExactly(SessionError.ERROR_INVALID_STATE)
            // The cover it was shown, fetched and kept in the first profile's folder, is refused from now on.
            assertThat(served(coverUri(ann.id, AnnBook)).exceptionOrNull())
                .isInstanceOf(FileNotFoundException::class.java)

            CarAtTheService(service()).assertShows(bob.id, BobBook)
            assertThat(served(coverUri(bob.id, BobBook)).getOrThrow())
                .isEqualTo("/api/v1/books/$BobBook/thumbnail for ${bearerFromServer(Bob)}")
            // The first profile's book under the new profile's name: the new profile's server has no such cover.
            assertThat(served(coverUri(bob.id, AnnBook)).exceptionOrNull())
                .isInstanceOf(FileNotFoundException::class.java)
            // All of it asked of the new profile's server, as its account: the first one's heard nothing.
            assertThat(anns.heard).isEqualTo(heardByAnns)
            assertThat(anns.tokensShown()).containsExactly(bearerFromServer(Ann))
            assertThat(bobs.tokensShown()).containsExactly(bearerFromServer(Bob))
        }
}
