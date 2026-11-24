package app.tfdn.lirelle.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.downloads.di.DownloadsProfileEntry
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.fileName
import app.tfdn.lirelle.core.library.di.LibraryProfileEntry
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.di.ProfileDataOnDevice
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import app.tfdn.lirelle.feature.reader.di.ReaderProfileEntry
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Ann = 5L
private const val Bob = 6L

/** A book file both accounts read: the same id on two servers is two books, each with its own place. */
private val SameFile = FileId("77")
private val AnnBook = BookId("41")
private val BobBook = BookId("42")
private const val Halfway = 0.5
private const val AudioBytes = 300
private const val AsksWhileTheOtherSleeps = 10

/** Where the reader's positions are sent. */
private const val PositionsPath = "/reading-progress"

/** The tokens the reader's positions were sent with. */
private fun RecordingServer.positionsSentAs() = authorizationsForPathsEnding(PositionsPath)

/** The files a profile keeps of its downloads, under its own folder, as the disk has them. */
private fun ProfileFolders.downloadPaths(): List<String> =
    File(files, "downloads").walkTopDown().filter { it.isFile }.map { it.path }.toList()

/**
 * What one account leaves that the other must never read: the place it is at in a book both have, a text it
 * searched, and a book it keeps on the device — its record and its file.
 *
 * @param tag what tells this account's place and search from the other's.
 * @param bookId the book it keeps, which the other does not.
 */
private class Own(private val view: GraphView, private val tag: String, private val bookId: BookId) {

    private val place = "epubcfi(/6/$tag)"

    private fun positions() = view.profile<ReaderCoreProfileEntry>().readingPositionStore()

    private fun searches() = view.profile<LibraryProfileEntry>().recentSearches()

    private fun records() = view.profile<ProfileDataOnDevice>().downloadRegistry()

    private fun record(): DownloadedBook {
        val bytes = AudioBytes.toLong()
        val asset = FileSource.AudioAsset("asset-$tag")
        val audio = PlannedFile("a1", asset, "v1", bytes, resumable = true, kind = FileKind.Audio)
        val format = FormatRecord(
            format = DownloadFormat.Audiobook,
            state = FormatState.Complete(AudioBytes.toLong()),
            files = listOf(audio),
            fileId = null,
            version = "v1",
            askedAt = Instant.EPOCH,
        )
        // Gone from its server: kept as it is, and nothing asks the server about it again.
        return DownloadedBook(bookId, listOf(format), Instant.EPOCH, lastOpenedAt = null, gone = true)
    }

    suspend fun leave() {
        positions().put(LocalPosition(SameFile, place, Halfway, Instant.EPOCH))
        searches().record(tag)
        records().update(bookId) { record() }
        view.profile<DownloadsProfileEntry>().downloadFiles().file(bookId, DownloadFormat.Audiobook, "a1")
            .apply { parentFile?.mkdirs() }
            .writeBytes(ByteArray(AudioBytes))
    }

    /** Through the objects the profile has now: what it left, and nothing another left. */
    suspend fun assertOnlyOwn() {
        assertWithMessage("$tag: place").that(positions().get(SameFile)?.cfi).isEqualTo(place)
        assertWithMessage("$tag: searches").that(searches().entries.first()).containsExactly(tag)
        assertWithMessage("$tag: kept books").that(records().books.first().map { it.bookId }).containsExactly(bookId)
        assertWithMessage("$tag: files").that(view.folders.downloadPaths().single()).contains("/${bookId.fileName}/")
    }

    suspend fun assertNothing() {
        assertWithMessage("$tag: place").that(positions().get(SameFile)).isNull()
        assertWithMessage("$tag: searches").that(searches().entries.first()).isEmpty()
        assertWithMessage("$tag: kept books").that(records().books.first()).isEmpty()
        assertWithMessage("$tag: files").that(view.folders.downloadPaths()).isEmpty()
    }
}

/**
 * Two accounts on one device, as the app itself keeps them: each signed in through the sign-in screens' own
 * view models against its own server, the app going from one to the other as a tap on a profile asks it to
 * (`ProfileStore.switchTo`). On the application's real graph and registry, with the device on a network: what
 * the profile left does not send, it does not send because it sleeps.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSwitchingTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val its = RecordingServer(signsIn = true)
    private val others = RecordingServer(signsIn = true)

    @Before
    fun deviceOnline() = context.deviceOnline()

    @After
    fun close(): Unit = runBlocking {
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed and dropped") { device.open() == null && device.listed()?.isEmpty() == true }
        its.close()
        others.close()
    }

    /** A page turned in the reader: kept to be sent, and the sending asked for. */
    private suspend fun GraphView.reads() {
        val reading = profile<ReaderCoreProfileEntry>()
        reading.readingOutbox().enqueuePosition(LocalPosition(SameFile, "epubcfi(/6/8)", Halfway, Instant.now()))
        reading.readingOutboxFlusher().kick()
    }

    /** What a screen or a task of the profile asks a server, as its account. */
    private suspend fun GraphView.asks(server: RecordingServer) = withContext(Dispatchers.IO) {
        val client = profile<ProfileDataOnDevice>().authenticatedClient()
        runCatching { client.newCall(Request.Builder().url(server.url("/api/v1/probe")).build()).execute().close() }
    }

    @Test
    fun `two accounts signed in one after the other each find only their own at every switch, on disk too`(): Unit =
        runBlocking {
            val ann = device.signsInOn(its, Ann)
            val annFolders = ann.folders
            Own(ann, "ann", AnnBook).leave()

            val bob = device.signsInOn(others, Bob)
            val bobFolders = bob.folders
            // The account that comes second finds a device that never knew the first.
            Own(bob, "bob", BobBook).assertNothing()
            Own(bob, "bob", BobBook).leave()
            assertThat(device.data.profileStore().profiles.value.map { it.id })
                .containsExactly(bob.id, ann.id).inOrder()

            val annAgain = device.switchesTo(ann.id)
            Own(annAgain, "ann", AnnBook).assertOnlyOwn()
            val bobAgain = device.switchesTo(bob.id)
            Own(bobAgain, "bob", BobBook).assertOnlyOwn()
            Own(device.switchesTo(ann.id), "ann", AnnBook).assertOnlyOwn()

            // On the disk, whoever is open: two folders named by their profiles, each with its own book only.
            assertThat(annFolders.files).isNotEqualTo(bobFolders.files)
            assertThat(annFolders.cache).isNotEqualTo(bobFolders.cache)
            assertThat(listOf(annFolders.files.name, bobFolders.files.name)).containsExactly(ann.id, bob.id).inOrder()
            assertThat(annFolders.downloadPaths().single()).contains("/${AnnBook.fileName}/")
            assertThat(bobFolders.downloadPaths().single()).contains("/${BobBook.fileName}/")
            assertThat(device.profileFolders().map { it.name }.distinct()).containsExactly(ann.id, bob.id)
        }

    @Test
    fun `the profile left has no live object, and its server hears nothing while the other is used`(): Unit =
        runBlocking {
            val ann = device.signsInOn(its, Ann)
            ann.reads()
            until("the first profile speaks to its server as its account") {
                bearerFromServer(Ann) in its.positionsSentAs()
            }
            // Refused by a server that is unavailable: the place still waits in the profile, to be sent again.
            val scopes = listOf(
                ann.profile<ReaderProfileEntry>().readerLeaveScope(),
                ann.profile<PlayerProfileEntry>().playerControllerScope(),
            )

            val bob = device.signsInOn(others, Bob)

            // Nothing of the profile left is alive: its objects are nobody's, and what they ran in has ended.
            assertThat(device.open()).isSameInstanceAs(bob.graph)
            assertThat(device.open()).isNotSameInstanceAs(ann.graph)
            assertThat(device.data.openProfiles().lastClosed).isNull()
            scopes.forEach { scope ->
                assertWithMessage("a scope of the profile left").that(scope.coroutineContext.job.isActive).isFalse()
            }
            until("only the profile shown follows the device") {
                device.data.deviceSignals().followers == FollowsOfAStartedProfile
            }
            // From here the second profile is used, on a device that has a network, while the first has
            // something to send: whatever the first's server hears now, a profile that sleeps has sent.
            until("the second profile speaks to its own server") { bearerFromServer(Bob) in others.tokensShown() }
            val heardFromTheSleeper = its.heard
            val placesSentByTheSleeper = its.positionsSentAs().size
            bob.reads()
            until("the second profile's place is sent") { bearerFromServer(Bob) in others.positionsSentAs() }
            repeat(AsksWhileTheOtherSleeps) { bob.asks(others) }

            assertThat(its.heard).isEqualTo(heardFromTheSleeper)
            assertThat(its.tokensShown()).containsExactly(bearerFromServer(Ann))
            assertThat(others.tokensShown()).containsExactly(bearerFromServer(Bob))

            // Current again, it speaks again: it was silent because it slept, not because it had nothing to say.
            device.switchesTo(ann.id)
            until("the profile that slept sends the place that waited") {
                its.positionsSentAs().size > placesSentByTheSleeper
            }
            assertThat(its.positionsSentAs().distinct()).containsExactly(bearerFromServer(Ann))
            assertThat(its.tokensShown()).containsExactly(bearerFromServer(Ann))
        }
}
