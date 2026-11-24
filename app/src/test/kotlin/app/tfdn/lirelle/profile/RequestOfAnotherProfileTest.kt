package app.tfdn.lirelle.profile

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.MainActivity
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.downloads.job.DownloadsIntents
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.service.PlayerSessionContract
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Book = BookId("42")

/**
 * A notification's tap names the profile that posted it. On a device with two profiles, the real activity and
 * the real registry: a tap that names the profile that is not the current one is not followed, and does not
 * make it current — the app never changes profile by itself; one that names the current profile is followed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TwoListedProfilesApplication::class)
class RequestOfAnotherProfileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)

    @After
    fun close() = removeFakeAndroidKeystore()

    private fun launcher(): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    // An intent holds no id: its text.
    private fun readerTap(profileId: String): Intent = launcher()
        .putExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID, Book.value)
        .putExtra(PlayerSessionContract.EXTRA_OPEN_READER_PROFILE_ID, profileId)

    private fun downloadsTap(profileId: String): Intent = launcher()
        .putExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS, true)
        .putExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID, profileId)

    /** The activity a tap starts, once the profile the registry names as current is the open one. */
    private suspend fun startedBy(tap: Intent): MainActivity {
        awaited("the current profile of the registry is open") {
            device.data.openProfiles().current.value?.takeIf { it.profileId == SecondProfile }
        }
        return Robolectric.buildActivity(MainActivity::class.java, tap).create().get()
    }

    private fun assertStillOnTheSecondProfile() {
        assertThat(device.data.profileStore().current.value?.id).isEqualTo(SecondProfile)
        assertThat(device.data.openProfiles().current.value?.profileId).isEqualTo(SecondProfile)
        assertThat(device.data.profileStore().profiles.value.map { it.id })
            .containsExactly(SecondProfile, FirstProfile).inOrder()
    }

    @Test
    fun `a reader asked for by the other profile's notification opens nothing and changes no profile`(): Unit =
        runBlocking {
            val activity = startedBy(readerTap(FirstProfile))

            assertThat(activity.readerRequests.take()).isNull()
            assertThat(activity.readerRequests.pending).isNull()
            assertStillOnTheSecondProfile()
        }

    @Test
    fun `the downloads asked for by the other profile's notification show nothing and change no profile`(): Unit =
        runBlocking {
            val activity = startedBy(downloadsTap(FirstProfile))

            assertThat(activity.downloadsRequests.take()).isFalse()
            assertThat(activity.downloadsRequests.pending).isFalse()
            assertStillOnTheSecondProfile()
        }

    @Test
    fun `what the current profile's own notifications ask for is followed`(): Unit = runBlocking {
        val reader = startedBy(readerTap(SecondProfile))
        assertThat(reader.readerRequests.take()).isEqualTo(Book)

        val downloads = startedBy(downloadsTap(SecondProfile))
        assertThat(downloads.downloadsRequests.take()).isTrue()
        assertStillOnTheSecondProfile()
    }
}
