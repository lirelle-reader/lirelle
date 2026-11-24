package app.tfdn.lirelle

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.downloads.job.DownloadsIntents
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The Downloads destination a notification of the downloads asks for: handed to the screens once, from a cold
 * or a warm start.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadsRequestIntentTest {

    private fun launcher(): Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    private fun notificationTap(flags: Int = 0): Intent =
        launcher().putExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS, true).addFlags(flags)

    @Test
    fun `a notification of the downloads asks for the Downloads destination, once`() {
        val intent = notificationTap()

        assertThat(takeDownloadsRequest(intent)).isTrue()
        assertThat(intent.hasExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS)).isFalse()
        assertThat(takeDownloadsRequest(intent)).isFalse()
    }

    @Test
    fun `the launcher and no intent do not ask for it`() {
        assertThat(takeDownloadsRequest(launcher())).isFalse()
        assertThat(takeDownloadsRequest(null)).isFalse()
    }

    @Test
    fun `an intent replayed from the recent apps does not ask for it`() {
        val intent = notificationTap(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        assertThat(takeDownloadsRequest(intent)).isFalse()
        assertThat(intent.hasExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS)).isFalse()
    }

    // The activity is only created below: its screens are not composed yet, so the request is seen as delivered.

    @Test
    fun `a cold start from the notification takes the request out of its intent and hands it to the screens`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, notificationTap()).create()

        assertThat(controller.get().intent.hasExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS)).isFalse()
        assertThat(controller.get().downloadsRequests.pending).isTrue()
    }

    /** The notification of a profile that closed: its downloads are not shown for whoever is, or is not, signed in. */
    @Test
    fun `a notification of another profile than the open one asks for nothing`() {
        val tap = notificationTap().putExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID, "p1")
        val activity = Robolectric.buildActivity(MainActivity::class.java, tap).create().get()
        assertThat(activity.intent.hasExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID)).isFalse()
        assertThat(activity.downloadsRequests.pending).isTrue()

        assertThat(activity.downloadsRequests.take()).isFalse()
        assertThat(activity.downloadsRequests.pending).isFalse()
    }

    @Test
    fun `a tap on the notification while the app runs hands the request to the screens`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, launcher()).create()
        assertThat(controller.get().downloadsRequests.pending).isFalse()

        controller.newIntent(notificationTap())

        assertThat(controller.get().downloadsRequests.pending).isTrue()
    }

    @Test
    fun `an activity made again from its saved state does not deliver its intent again`() {
        val intent = launcher()
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        // As if its intent still asked for the downloads: only the saved state keeps it from being delivered again.
        intent.putExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS, true)

        controller.recreate()

        assertThat(controller.get().downloadsRequests.pending).isFalse()
    }
}
