package app.tfdn.lirelle

import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.downloads.job.DownloadsIntents
import app.tfdn.lirelle.di.DownloadsIntentsModule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What a notification of the downloads opens. */
@RunWith(RobolectricTestRunner::class)
class DownloadsIntentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notifications of the downloads open the app, asking for its Downloads destination`() {
        val pending = shadowOf(DownloadsIntentsModule.downloadsIntents(context).openDownloads("p1"))

        assertThat(pending.isActivity).isTrue()
        assertThat(pending.flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
        assertThat(pending.savedIntent.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(pending.savedIntent.getBooleanExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS, false)).isTrue()
    }

    /**
     * The system keeps one pending intent for intents it holds equal, and extras do not tell two apart: the
     * notification of a profile left a moment ago must not come to name the one opened since.
     */
    @Test
    fun `they name the profile they are of, each profile's with an intent of its own`() {
        val intents = DownloadsIntentsModule.downloadsIntents(context)
        val first = intents.openDownloads("p1")
        val second = intents.openDownloads("p2")

        assertThat(first).isNotEqualTo(second)
        assertThat(intents.openDownloads("p1")).isEqualTo(first)
        assertThat(shadowOf(first).savedIntent.getStringExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID))
            .isEqualTo("p1")
        assertThat(shadowOf(second).savedIntent.getStringExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID))
            .isEqualTo("p2")
    }
}
