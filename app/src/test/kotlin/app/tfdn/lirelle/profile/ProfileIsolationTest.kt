package app.tfdn.lirelle.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.comics.di.ComicPagesProfileEntry
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.di.AppProfileEntry
import app.tfdn.lirelle.di.ProfileDataOnDevice
import app.tfdn.lirelle.signout.BookSpeed
import app.tfdn.lirelle.signout.DownloadTraces
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.ListeningTraces
import app.tfdn.lirelle.signout.ReadingTraces
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.TestServer
import app.tfdn.lirelle.signout.TracedBook
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val BackSeconds = 45

/** A book file that only these tests read: its place is written apart from what the traces leave. */
private val OwnFile = FileId("99")
private const val Halfway = 0.5

/**
 * What every profile follows of the device once it has started: the five follow-ups of the profile's starters
 * (`ProfileStartersModule`). A starter that follows one more changes this number, and nothing else here.
 */
private const val FollowsOfAProfile = 5

/** Everything an account leaves by using the app, through the profile looked at by [view]. */
private fun traces(view: GraphView) = listOf(DownloadTraces(view), ReadingTraces(view), ListeningTraces(view))

private fun place(cfi: String) = LocalPosition(OwnFile, cfi, Halfway, Instant.EPOCH)

/** The profile takes a place and gives it back, and its caches open: nothing of its files is held elsewhere. */
private suspend fun GraphView.assertReadsAndWrites() {
    val positions = profile<ReaderCoreProfileEntry>().readingPositionStore()
    positions.put(place("again"))
    assertWithMessage("a place written").that(positions.get(OwnFile)?.cfi).isEqualTo("again")
    val covers = checkNotNull(profile<AppProfileEntry>().coverImageLoader().diskCache)
    assertWithMessage("a cover written").that(covers.openEditor("$TestServer/again")?.also { it.abort() }).isNotNull()
    // Opens the folder of the audio cache and its index: refused while other objects still hold them.
    assertWithMessage("audio cache").that(profile<ProfileDataOnDevice>().audioCache().cache.cacheSpace).isAtLeast(0)
}

/**
 * Two profiles open in one process share nothing but the device: what keeping several accounts on it stands
 * on. The app keeps several and opens one at a time — a profile that is not current sleeps — so nothing in it
 * ever has two open at once, and only this says they share nothing. On the application's real graph: a
 * binding left in the wrong component, or a store that looks for its file by itself, shows here and nowhere
 * else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TwoListedProfilesApplication::class)
class ProfileIsolationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val profiles = TwoProfiles(device)
    private val servers = mutableListOf<RecordingServer>()

    @After
    fun close(): Unit = runBlocking {
        profiles.closeAll()
        servers.forEach { it.close() }
        removeFakeAndroidKeystore()
    }

    @Test
    fun `no object of a profile is another profile's`(): Unit = runBlocking {
        val (first, second) = profiles.open()
        val accessors = profileAccessors(first.graph.component)

        // The walk found the entry points: without this, sharing nothing could be finding nothing.
        assertThat(accessors.map { it.name }).containsAtLeast(
            "ReaderCoreProfileEntry.readingPositionStore",
            "ProfileDataOnDevice.authenticatedClient",
            "PlayerProfileEntry.playerController",
        )
        assertWithMessage("accessors that give two profiles the same object")
            .that(accessors.objectsOf(first).sharedWith(accessors.objectsOf(second)))
            .containsExactlyElementsIn(SharedByEveryProfile.keys)
    }

    @Test
    fun `what one profile keeps is not read through the other, and lies in its own folders`(): Unit = runBlocking {
        val (first, second) = profiles.open()

        traces(first).forEach { it.leave() }
        traces(first).forEach { it.assertLeft() }

        traces(second).forEach { it.assertNone() }
        val folders = listOf(first.folders.files, first.folders.cache, second.folders.files, second.folders.cache)
        assertThat(folders.map { it.name }).containsExactly(first.id, first.id, second.id, second.id).inOrder()
        assertThat(first.id).isNotEqualTo(second.id)

        // And the other way: the second writes its own, which leaves the first's as they were.
        traces(second).forEach { it.leave() }
        second.profile<ReaderCoreProfileEntry>().readingPositionStore().put(place("second"))
        traces(second).forEach { it.assertLeft() }
        traces(first).forEach { it.assertLeft() }
        assertThat(first.profile<ReaderCoreProfileEntry>().readingPositionStore().get(OwnFile)).isNull()
    }

    @Test
    fun `a setting of the device changed in one profile is the other's too`(): Unit = runBlocking {
        val (first, second) = profiles.open()
        val player = first.profile<ProfileDataOnDevice>().playerSettingsStore()
        val otherPlayer = second.profile<ProfileDataOnDevice>().playerSettingsStore()
        val comics = first.profile<ComicPagesProfileEntry>().comicSettingsStore()
        val otherComics = second.profile<ComicPagesProfileEntry>().comicSettingsStore()

        player.update { it.copy(backSeconds = BackSeconds) }
        player.setSpeed(TracedBook, BookSpeed)
        comics.setFit(Fit.Width)

        assertThat(otherPlayer.settings.first().backSeconds).isEqualTo(BackSeconds)
        assertThat(device.data.playerDeviceSettings().settings.first().backSeconds).isEqualTo(BackSeconds)
        // The speed of a book is its profile's; the speed of the next new book is the device's.
        assertThat(otherPlayer.speedOf(TracedBook)).isNull()
        assertThat(otherPlayer.settings.first().lastSpeed).isEqualTo(BookSpeed)
        assertThat(otherComics.shared.first().fit).isEqualTo(Fit.Width)
        assertThat(otherComics.settings(DirectionScope.Series(SeriesId("1"))).first().fit).isEqualTo(Fit.Width)
        assertThat(device.data.comicDeviceSettings().shared.first().fit).isEqualTo(Fit.Width)
    }

    @Test
    fun `each profile shows its token to its own server and to no other`(): Unit = runBlocking {
        val its = RecordingServer().also { servers += it }
        val others = RecordingServer().also { servers += it }
        val (first, second) = profiles.openOn(its.address, others.address)
        suspend fun GraphView.asks(url: HttpUrl) = withContext(Dispatchers.IO) {
            val client = profile<ProfileDataOnDevice>().authenticatedClient()
            client.newCall(Request.Builder().url(url).build()).execute().close()
        }

        first.asks(its.url("/own"))
        first.asks(others.url("/foreign"))
        second.asks(others.url("/own"))
        second.asks(its.url("/foreign"))

        assertThat(its.authorizationsFor("/own")).containsExactly(bearerOf(userId = 3))
        assertThat(others.authorizationsFor("/own")).containsExactly(bearerOf(userId = 4))
        assertThat(its.authorizationsFor("/foreign")).containsExactly(null)
        assertThat(others.authorizationsFor("/foreign")).containsExactly(null)
        // Whatever the profiles asked by themselves meanwhile, as they started: no server saw the other's token.
        assertThat(its.tokensShown()).containsExactly(bearerOf(userId = 3))
        assertThat(others.tokensShown()).containsExactly(bearerOf(userId = 4))
    }

    @Test
    fun `a profile that closes lets its folders go, and the other still reads and writes`(): Unit = runBlocking {
        val (first, second) = profiles.open()
        traces(first).forEach { it.leave() }
        traces(second).forEach { it.leave() }
        val gone = first.folders

        // The second account signed in last: the first is not the registry's current one.
        assertThat(device.data.profileStore().removeSleeping(first.id)).isTrue()
        profiles.close(first)

        until("the folders of the closed profile are deleted") {
            !gone.files.exists() && !gone.cache.exists() && device.listed() == setOf(second.id)
        }
        traces(second).forEach { it.assertLeft() }
        second.profile<ReaderCoreProfileEntry>().readingPositionStore().put(place("after"))
        assertThat(second.profile<ReaderCoreProfileEntry>().readingPositionStore().get(OwnFile)?.cfi)
            .isEqualTo("after")
        assertThat(device.profileFolders().map { it.name }.distinct()).containsExactly(second.id)
    }

    /**
     * What the app itself does when a profile that is not the one shown is removed: nothing of it is open, so
     * nothing closes; its folders go without waiting for a start, and the profile shown is not rebuilt.
     */
    @Test
    fun `a sleeping profile removed loses its folders at once, and the one shown still reads and writes`(): Unit =
        runBlocking {
            val (first, second) = profiles.open()
            traces(first).forEach { it.leave() }
            traces(second).forEach { it.leave() }
            val gone = first.folders
            // It sleeps from here on, whole in its folders, as a profile the app has left.
            profiles.close(first)
            assertThat(gone.files.exists()).isTrue()
            val shown = device.data.openProfiles().current.value

            assertThat(device.data.profileStore().removeSleeping(first.id)).isTrue()

            until("the folders of the sleeping profile are deleted and its record dropped") {
                !gone.files.exists() && !gone.cache.exists() && device.listed() == setOf(second.id)
            }
            assertThat(device.data.openProfiles().current.value).isSameInstanceAs(shown)
            assertThat(device.data.openProfiles().shown.value.profileId).isEqualTo(second.id)
            traces(second).forEach { it.assertLeft() }
            second.assertReadsAndWrites()
        }

    @Test
    fun `what starts with a profile follows the device until that profile closes`(): Unit = runBlocking {
        val signals = device.data.deviceSignals()
        until("the profile the app opened follows the device") { signals.followers == FollowsOfAProfile }

        val (first, _) = profiles.open()
        until("both profiles follow the device") { signals.followers == 2 * FollowsOfAProfile }

        // Closing waits for what the profile was doing: nothing of it is left following.
        profiles.close(first)
        assertThat(signals.followers).isEqualTo(FollowsOfAProfile)

        device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        // The profile used before the one removed is shown in its place, and follows the device in its turn.
        until("the profile that is left is the one shown") {
            device.data.openProfiles().current.value?.profileId == FirstProfile
        }
        until("only the profile shown follows the device") { signals.followers == FollowsOfAProfile }

        device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        until("the last profile no longer follows the device") { signals.followers == 0 }
    }

    /**
     * What keeping several accounts needs most: going back to one. Its files must have been let go of when it
     * closed — a preferences file, the folder of a cache, the index of the audio — or the same process cannot
     * open them a second time.
     */
    @Test
    fun `a profile closed and kept is opened again in the same process, as it was`(): Unit = runBlocking {
        val (first, _) = profiles.open()
        listOf(ReadingTraces(first), ListeningTraces(first)).forEach { it.leave() }
        DownloadTraces(first).leave()
        val accessors = profileAccessors(first.graph.component)
        // Every object the profile can give is made, so that every file it can hold is held.
        val closed = accessors.objectsOf(first)

        profiles.close(first)
        val again = profiles.openBeside(first.id)

        assertThat(again.folders).isEqualTo(first.folders)
        assertWithMessage("objects of the closed profile given to the one opened again")
            .that(closed.sharedWith(accessors.objectsOf(again)))
            .containsExactlyElementsIn(SharedByEveryProfile.keys)
        listOf(ReadingTraces(again), ListeningTraces(again)).forEach { it.assertLeft() }
        assertThat(again.profile<ProfileDataOnDevice>().downloadCheckClock().last()).isEqualTo(Instant.EPOCH)
        again.assertReadsAndWrites()
    }

    /**
     * What the app itself does: an account that signs in while another is signed in comes beside it. The
     * profile left is closed and kept whole; nothing of it is there for the new one to find, and everything
     * is, for its own account when it signs in again: it is the same profile then.
     */
    @Test
    fun `an account that signs in beside another leaves it whole, and the first comes back to what it left`(): Unit =
        runBlocking {
            val (_, second) = profiles.open()
            listOf(ReadingTraces(second), ListeningTraces(second)).forEach { it.leave() }
            val kept = second.folders

            val third = profiles.show(userId = 3)

            // Both are kept, the one that was open and the one that was not, and neither waits to be removed.
            assertThat(device.data.profileStore().profile(SecondProfile)).isNotNull()
            assertThat(device.data.profileStore().profile(FirstProfile)).isNotNull()
            assertThat(device.data.profileStore().beingRemoved()).isEmpty()
            assertWithMessage("the folders of the profile left").that(kept.files.exists()).isTrue()
            assertThat(device.data.profileStore().profiles.value.map { it.id })
                .containsExactly(third.id, SecondProfile, FirstProfile).inOrder()
            listOf(ReadingTraces(third), ListeningTraces(third)).forEach { it.assertNone() }

            val again = profiles.show(userId = 2, OtherServer)

            assertThat(again.id).isEqualTo(SecondProfile)
            listOf(ReadingTraces(again), ListeningTraces(again)).forEach { it.assertLeft() }
            again.assertReadsAndWrites()
        }
}
