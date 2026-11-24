package app.tfdn.lirelle.profile

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.di.LibraryProfileEntry
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.sync.PositionSyncFactory
import app.tfdn.lirelle.core.reader.sync.SessionClockFactory
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import app.tfdn.lirelle.feature.player.service.LastBook
import app.tfdn.lirelle.feature.player.service.LastBookKeeper
import app.tfdn.lirelle.feature.player.service.PlaybackService
import app.tfdn.lirelle.feature.player.service.PlaybackServiceRunning
import app.tfdn.lirelle.feature.reader.ReadingSession
import app.tfdn.lirelle.feature.reader.ReadingSessionFactory
import app.tfdn.lirelle.feature.reader.di.ReaderProfileEntry
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.ListeningTraces
import app.tfdn.lirelle.signout.ReadingTraces
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

private val ReadFile = FileId("78")
private const val OpenedAt = "epubcfi(/6/2)"
private const val LeftAt = "epubcfi(/6/14)"
private const val AtTheStart = 0.1
private const val FurtherOn = 0.4
private val HeardBook = BookId("41")

/**
 * How long the main thread does nothing else at a switch: long enough for the closing to start, shorter than
 * the few seconds a closer is given (`CloseWaitMillis`).
 */
private val MainThreadBusy = 1.5.seconds

/** The action the system's media controls bind the service with: the session is asked for at once. */
private const val BrowserAction = "android.media.browse.MediaBrowserService"

/**
 * The reading of one book on screen, made of the profile's objects exactly as a reader's view model is given
 * them through its bridges: the profile's positions, its outbox, and the scope its last writes run in.
 *
 * @param screen the view model's scope: it ends when the reader's screen is left.
 */
private fun GraphView.readingOf(fileId: FileId, screen: CoroutineScope): ReadingSession {
    val reading = profile<ReaderCoreProfileEntry>()
    val wall = Clock.systemUTC()
    val positions = PositionSyncFactory(
        profile<LibraryProfileEntry>().libraryReaderRepository(),
        reading.readingPositionStore(),
        reading.readingOutbox(),
        reading.readingOutboxFlusher(),
        wall,
    )
    val clocks = SessionClockFactory(MonotonicClock { SystemClock.elapsedRealtime() }, wall)
    val leaving = profile<ReaderProfileEntry>().readerLeaveScope()
    return ReadingSessionFactory(positions, clocks, reading.readingOutbox(), reading.readingOutboxFlusher(), leaving)
        .create(fileId, screen)
}

private fun GraphView.lastBooks() = profile<PlayerProfileEntry>().playbackParts().lastBooks

private fun GraphView.lastBookMark() = profile<PlayerProfileEntry>().lastBookMark()

/**
 * What a profile left must find when it is the current one again, on the application's real graph: the app
 * goes from one of its two profiles to the other as a tap asks it to (`ProfileStore.switchTo`), closes the one
 * left and opens the other by itself. Their servers do not exist: everything here is the device's own doing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TwoListedProfilesApplication::class)
class ProfileLeavingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val services = mutableListOf<ServiceController<PlaybackService>>()

    @After
    fun close(): Unit = runBlocking {
        // The system ends a service that stopped itself and that nothing is bound to.
        services.forEach { it.destroy() }
        assertThat(PlaybackServiceRunning.running.value).isFalse()
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed and dropped") { device.open() == null && device.listed()?.isEmpty() == true }
        removeFakeAndroidKeystore()
    }

    /** The service, created and started as the system does it for the profile that is open. */
    private fun serviceStarted(): PlaybackService =
        Robolectric.buildService(PlaybackService::class.java).also { services += it }.create().get()
            .also { it.onStartCommand(Intent(), 0, 1) }

    /** The session a controller is given, as the system's media controls ask for it; null when refused. */
    private fun PlaybackService.session() = onBind(Intent(BrowserAction))

    private fun Service.stopped() = shadowOf(this).isStoppedBySelf

    /**
     * Going back to a profile is opening it a second time in one process: every file it held must have been
     * let go of at the switch, and nothing of the first opening may be handed to the second.
     */
    @Test
    fun `a profile switched away from and back to is made of new objects, which read what the old ones wrote`(): Unit =
        runBlocking {
            val before = device.shows(SecondProfile)
            listOf(ReadingTraces(before), ListeningTraces(before)).forEach { it.leave() }
            val accessors = profileAccessors(before.graph.component)
            // Every object the profile can give is made, so that every file it can hold is held.
            val objectsBefore = accessors.objectsOf(before)

            device.switchesTo(FirstProfile)
            val again = device.switchesTo(SecondProfile)

            assertThat(again.graph).isNotSameInstanceAs(before.graph)
            assertThat(again.graph.component).isNotSameInstanceAs(before.graph.component)
            assertThat(again.folders).isEqualTo(before.folders)
            assertWithMessage("objects of the first opening given to the second")
                .that(objectsBefore.sharedWith(accessors.objectsOf(again)))
                .containsExactlyElementsIn(SharedByEveryProfile.keys)
            listOf(ReadingTraces(again), ListeningTraces(again)).forEach { it.assertLeft() }
        }

    /**
     * A switch can only be asked from Home, Library or More: the reader was left just before. Its last write
     * starts as its screen is left, and the closing of the profile waits for it (`waitForLastWrites`): the
     * profile's stores end with it, and would cut a write still on its way. Without the leaving, the place
     * would still wait for the page to stay still.
     *
     * Part of that write runs on the main thread, which is the test's: it is kept busy for a while at the
     * switch, as on a loaded device or runner, so that the closing is under way before the write can end.
     */
    @Test
    fun `the place a reader was left at is on disk after a switch asked right after, kept to be sent`(): Unit =
        runBlocking {
            val reading = device.shows(SecondProfile)
            val screen = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val session = reading.readingOf(ReadFile, screen)
            session.restore()
            session.relocated(OpenedAt, AtTheStart, moved = false)
            session.relocated(LeftAt, FurtherOn, moved = true)
            val outbox = reading.profile<ReaderCoreProfileEntry>().readingOutbox()
            assertWithMessage("nothing is handed over while the page may still move")
                .that(outbox.pending().positions).isEmpty()

            // Back to Home — the reader's view model is cleared — and a tap on the other profile at once.
            session.close()
            screen.cancel()
            device.data.profileStore().switchTo(FirstProfile)
            Thread.sleep(MainThreadBusy.inWholeMilliseconds)
            val other = device.shows(FirstProfile)

            assertThat(other.profile<ReaderCoreProfileEntry>().readingPositionStore().get(ReadFile)).isNull()
            val again = device.switchesTo(SecondProfile).profile<ReaderCoreProfileEntry>()
            assertThat(again.readingPositionStore().get(ReadFile)?.cfi).isEqualTo(LeftAt)
            assertThat(again.readingOutbox().pending().positions.map { it.fileId to it.cfi })
                .containsExactly(ReadFile to LeftAt)
        }

    /**
     * At the level of the profile's own objects — the store the playback service is made of
     * (`PlaybackParts.lastBooks`), the mark the play button's receiver looks at, and the keeper the service
     * tells of a closing. A book cannot be loaded here: that needs a server and a player. That the service
     * tells its keeper "the profile closes" before it closes a loaded book is `BookFollowersTest`'s.
     */
    @Test
    fun `the last book of the profile left is its last book on return, and stopping listening forgets it`(): Unit =
        runBlocking {
            val heard = LastBook(HeardBook, "Mars", coverUrl = null)
            val listening = device.shows(SecondProfile)
            // An audiobook was opened: what the service's keeper writes then.
            listening.lastBooks().put(heard)
            assertThat(listening.lastBookMark().exists()).isTrue()

            val other = device.switchesTo(FirstProfile)

            // A play button pressed now finds no book: the other profile's is not this one's.
            assertThat(other.lastBooks().get()).isNull()
            assertThat(other.lastBookMark().exists()).isFalse()

            val again = device.switchesTo(SecondProfile)

            assertThat(again.lastBooks().get()).isEqualTo(heard)
            assertThat(again.lastBookMark().exists()).isTrue()

            // "Stop listening": the user's closing, which the keeper is told without "the profile closes".
            val lasting = again.profile<PlayerProfileEntry>().playerControllerScope()
            LastBookKeeper(again.lastBooks(), lasting).closed()

            until("the last book is forgotten, with its mark") {
                again.lastBooks().get() == null && !again.lastBookMark().exists()
            }
            device.switchesTo(FirstProfile)
            assertThat(device.switchesTo(SecondProfile).lastBooks().get()).isNull()
        }

    /**
     * As far as a test without a player goes: the service made for a profile ends when the app goes to
     * another, gives no session from then on, and one made afterwards is the new profile's. That the app's
     * controller tries again until the service is a new one is `ConnectionRetryTest`'s; what plays is the
     * device smoke's.
     */
    @Test
    fun `the playback service of the profile left ends at the switch, and the next one is the new profile's`(): Unit =
        runBlocking {
            device.shows(SecondProfile)
            val service = serviceStarted()
            assertWithMessage("session").that(service.session()).isNotNull()
            assertThat(service.stopped()).isFalse()

            device.switchesTo(FirstProfile)

            until("the service of the profile left stops itself") { service.stopped() }
            // Still there until the system ends it, with a profile open that is not its own: it refuses.
            assertWithMessage("session of the service left").that(service.session()).isNull()

            val next = serviceStarted()
            assertWithMessage("session of the next service").that(next.session()).isNotNull()
            assertThat(next.stopped()).isFalse()
            // And it is the profile's that is current now: it ends with it, in its turn.
            device.switchesTo(SecondProfile)
            until("the next service stops itself when its own profile is left") { next.stopped() }
        }
}
