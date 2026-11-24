package app.tfdn.lirelle.feature.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.downloads.job.Batch
import app.tfdn.lirelle.core.downloads.job.DownloadNotifications
import app.tfdn.lirelle.core.downloads.job.QueueNotifications
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * The texts of the two notifications of the queue, at any width, language or text size. The system lays a
 * notification out, not the application: its texts are taken from the notifications as built and laid out here
 * as plain texts, between the margins a notification has, where they must be whole and break between words.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class QueueNotificationsTextFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val notifications by lazy {
        val context = compose.activity
        val open = PendingIntent.getActivity(context, 0, Intent("open-downloads"), PendingIntent.FLAG_IMMUTABLE)
        QueueNotifications(context, DownloadNotifications(context, "p1") { open })
    }

    private fun Notification.texts(): List<String> = listOfNotNull(
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
    ) + actions.orEmpty().map { it.title.toString() }

    private fun assertFits(vararg shown: Notification) {
        val texts = shown.flatMap { it.texts() }
        compose.setContent {
            LirelleTheme {
                // More texts than a small screen is high: the column scrolls, as the shade does.
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    texts.forEach { Text(it) }
                }
            }
        }

        assertThat(compose.assertAllTextFits().map { it.text }).containsExactlyElementsIn(texts)
    }

    @Test
    fun `waiting for Wi-Fi`() {
        assertFits(
            notifications.waiting(books = 1, leftBytes = 4 * Mb),
            notifications.waiting(books = 123, leftBytes = 125_300 * Mb),
            notifications.waiting(books = 123, leftBytes = null),
        )
    }

    @Test
    fun `a download made of pages, and one named by its file beside an audiobook`() {
        val context = compose.activity
        val open = PendingIntent.getActivity(context, 0, Intent("open-downloads"), PendingIntent.FLAG_IMMUTABLE)
        val progress = DownloadNotifications(context, "p1") { open }
        val formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
        fun entry(pages: PageCount?) = QueueEntry(
            BookId("7"),
            formats,
            1_240 * Mb,
            4_120 * Mb,
            failure = null,
            pages = pages,
            textName = TextName.Djvu,
        )

        assertFits(
            progress.progress(entry(PageCount(1_234, 4_321)), "A title long enough for the shade"),
            progress.progress(entry(PageCount(1, 1)), null),
            progress.progress(entry(pages = null), null),
        )
    }

    @Test
    fun `the queue is done`() {
        assertFits(
            notifications.done(Batch(books = 1, bytes = 4 * Mb, failed = 0)),
            notifications.done(Batch(books = 123, bytes = 125_300 * Mb, failed = 0)),
            notifications.done(Batch(books = 123, bytes = 125_300 * Mb, failed = 45)),
            notifications.done(Batch(books = 1, bytes = 4 * Mb, failed = 1)),
            notifications.done(Batch(books = 0, bytes = 0, failed = 45)),
        )
    }

    companion object {
        private const val Mb = 1_000_000L

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf("w320dp-h640dp", "w411dp-h891dp", "w1280dp-h800dp").flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
