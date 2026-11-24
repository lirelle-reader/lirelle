package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.cash.turbine.test
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DownloadNoticesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val open = PendingIntent.getActivity(context, 0, Intent("open-downloads"), PendingIntent.FLAG_IMMUTABLE)
    private val queue = MutableStateFlow(QueueState(null, emptyList(), null, null))
    private val asked = mutableListOf<BookId>()
    private val notices = DownloadNotices(
        queue = queue,
        title = { bookId -> "Book $bookId".also { asked += bookId } },
        notifications = DownloadNotifications(context, "p1") { open },
    )

    private val Notification?.title: String? get() = this?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun `the job shows the book being fetched, its title read once`() = runTest {
        notices.progress.test {
            assertThat(awaitItem().title).isEqualTo("Downloading")

            queue.value = QueueState(entry(bookId = BookId("7"), receivedBytes = 1), emptyList(), null, null)
            assertThat(awaitItem().title).isEqualTo("Book 7")
            queue.value = QueueState(entry(bookId = BookId("7"), receivedBytes = 2), emptyList(), null, null)
            assertThat(awaitItem().title).isEqualTo("Book 7")

            assertThat(asked).containsExactly(BookId("7"))
        }
    }
}
