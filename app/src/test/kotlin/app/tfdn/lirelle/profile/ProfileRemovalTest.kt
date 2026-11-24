package app.tfdn.lirelle.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.profile.ShownSession
import app.tfdn.lirelle.signout.DownloadTraces
import app.tfdn.lirelle.signout.ListeningTraces
import app.tfdn.lirelle.signout.ReadingTraces
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.TestServer
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PictureAddress = "/api/v1/users/1/avatar?v=1"
private val PictureBytes = byteArrayOf(4, 2)

/** What the screens were shown, without the tokens: whose profile, the wait of a change, or signed out. */
private fun ShownSession.told(): String = when {
    switching -> "wait"
    state is SessionState.SignedIn -> "profile $profileId"
    state is SessionState.SignedOut -> "signed out"
    else -> "loading"
}

/**
 * Removing a profile, on the application's real graph and registry, as the Profiles page does it: a profile
 * that sleeps by `ProfileStore.removeSleeping`, the current one by signing it out. The app deletes what the
 * removed profile kept, and shows what is left, by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TwoListedProfilesApplication::class)
class ProfileRemovalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val store get() = device.data.profileStore()
    private val avatars get() = device.data.profileAvatars()

    @After
    fun close(): Unit = runBlocking {
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed and dropped") { device.open() == null && device.listed()?.isEmpty() == true }
        removeFakeAndroidKeystore()
    }

    @Test
    fun `a sleeping profile removed loses its folders and its picture, and the open one keeps its objects`(): Unit =
        runBlocking {
            // The first profile is used, then left: it sleeps with everything an account leaves, and a picture.
            val used = device.switchesTo(FirstProfile)
            listOf(DownloadTraces(used), ReadingTraces(used), ListeningTraces(used)).forEach { it.leave() }
            val gone = used.folders
            avatars.keep(FirstProfile, PictureAddress, PictureBytes)
            val shown = device.switchesTo(SecondProfile)
            ReadingTraces(shown).leave()
            assertThat(gone.files.exists() && gone.cache.exists()).isTrue()
            assertThat(avatars.picture(FirstProfile).first()).isNotNull()
            val positions = shown.profile<ReaderCoreProfileEntry>().readingPositionStore()

            // Asked for the profile that is open, it removes nobody: that one is signed out, not swept.
            assertThat(store.removeSleeping(SecondProfile)).isFalse()
            assertThat(store.removeSleeping(FirstProfile)).isTrue()

            until("the folders and the picture of the sleeping profile are deleted, and its record dropped") {
                !gone.files.exists() && !gone.cache.exists() && device.listed() == setOf(SecondProfile) &&
                    avatars.keptFor(FirstProfile) == null
            }
            assertThat(avatars.picture(FirstProfile).first()).isNull()
            assertThat(store.profiles.value.map { it.id }).containsExactly(SecondProfile)
            // Nothing of the open profile moved: the same graph, the same component, the same objects.
            assertThat(device.open()).isSameInstanceAs(shown.graph)
            assertThat(device.open()?.component).isSameInstanceAs(shown.graph.component)
            assertThat(shown.profile<ReaderCoreProfileEntry>().readingPositionStore()).isSameInstanceAs(positions)
            assertThat(device.data.openProfiles().shown.value.told()).isEqualTo("profile $SecondProfile")
            ReadingTraces(shown).assertLeft()
            assertThat(device.profileFolders().map { it.name }.distinct()).containsExactly(SecondProfile)
        }

    @Test
    fun `removing the current profile of two shows the wait then the other, and removing the last signs out`(): Unit =
        runBlocking {
            // Both were used: each has its folders. The second is the current one, the first's server is TestServer.
            device.switchesTo(FirstProfile)
            val current = device.switchesTo(SecondProfile)
            val left = current.folders
            val told = CopyOnWriteArrayList<String>()
            val watching = launch(Dispatchers.Unconfined) {
                device.data.openProfiles().shown.collect { if (told.lastOrNull() != it.told()) told += it.told() }
            }

            device.data.sessions().clearCurrent(SignOutReason.UserRequest)

            device.shows(FirstProfile)
            until("what the removed profile kept is deleted, and its record dropped") {
                !left.files.exists() && !left.cache.exists() && device.listed() == setOf(FirstProfile)
            }
            assertWithMessage("what the screens were shown")
                .that(told).containsExactly("profile $SecondProfile", "wait", "profile $FirstProfile").inOrder()
            assertThat(store.profiles.value.map { it.id }).containsExactly(FirstProfile)

            device.data.sessions().clearCurrent(SignOutReason.UserRequest)

            until("the last profile is closed, its folders deleted and its record dropped") {
                device.open() == null && device.profileFolders().isEmpty() && device.listed()?.isEmpty() == true
            }
            watching.cancelAndJoin()
            // No wait this time: nobody is left to change to. The sign-in screen offers the server just left.
            assertWithMessage("what the screens were shown")
                .that(told).containsExactly("profile $SecondProfile", "wait", "profile $FirstProfile", "signed out")
                .inOrder()
            assertThat(device.serverOffered()).isEqualTo(TestServer)
            assertThat(device.data.sessions().serverUrl.value).isEqualTo(TestServer)
            assertThat(store.current.value).isNull()
        }
}
