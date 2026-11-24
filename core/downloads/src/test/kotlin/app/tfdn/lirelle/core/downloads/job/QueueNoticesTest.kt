package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueNoticesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val open = PendingIntent.getActivity(context, 0, Intent("open-downloads"), PendingIntent.FLAG_IMMUTABLE)
    private val queue = MutableStateFlow(QueueState(null, emptyList(), null, null))
    private val books = MutableStateFlow(emptyList<DownloadedBook>())
    private val notices =
        QueueNotices(context, queue, books, QueueNotifications(context, DownloadNotifications(context, "p1") { open }))

    private val failed = entry(bookId = BookId("9"), failure = DownloadFailure.Damaged)

    private fun waitingForWifi(count: Int, left: Long? = 666_000_000): QueueState {
        val waiting = listOf("1", "2", "3").take(count).map { entry(bookId = BookId(it)) }
        return QueueState(null, waiting, WaitReason.UnmeteredOnly, left)
    }

    private val waiting: Notification? get() = shadowOf(manager).getNotification(WaitingNotificationId)
    private val done: Notification? get() = shadowOf(manager).getNotification(DoneNotificationId)
    private val Notification?.text: String? get() = this?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun following(test: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.launch { notices.run() }
        test()
    }

    @Test
    fun `waiting for Wi-Fi is posted, follows the count and goes when the wait ends`() = following {
        assertThat(waiting).isNull()

        queue.value = waitingForWifi(2)
        assertThat(waiting.text).startsWith("2 books · ")

        queue.value = waitingForWifi(3)
        assertThat(waiting.text).startsWith("3 books · ")

        queue.value = QueueState(entry(bookId = BookId("1")), listOf(entry(bookId = BookId("2"))), null, 5)
        assertThat(waiting).isNull()
    }

    @Test
    fun `what failed is no work to wait for`() = following {
        queue.value = QueueState(null, listOf(failed), null, null)
        assertThat(waiting).isNull()

        queue.value = QueueState(null, listOf(failed, entry(bookId = BookId("1"))), WaitReason.UnmeteredOnly, 5)
        assertThat(waiting.text).startsWith("1 book · ")
    }

    @Test
    fun `no notification while waiting for any network, nor while one book is fetched`() = following {
        queue.value = QueueState(null, listOf(entry()), WaitReason.NoNetwork, 5)
        assertThat(waiting).isNull()

        queue.value = QueueState(entry(), listOf(entry(bookId = BookId("2"))), WaitReason.UnmeteredOnly, 5)
        assertThat(waiting).isNull()
    }

    @Test
    fun `swiped away, it is not posted again until the queue waits anew`() = following {
        queue.value = waitingForWifi(2)
        manager.cancel(WaitingNotificationId)

        queue.value = waitingForWifi(3)
        assertThat(waiting).isNull()

        queue.value = QueueState(entry(bookId = BookId("1")), emptyList(), null, 5)
        queue.value = waitingForWifi(1)
        assertThat(waiting.text).startsWith("1 book · ")
    }

    @Test
    fun `one done notification per batch, when the queue is empty`() = following {
        books.value = listOf(book("1", record(FormatState.Queued)), book("2", record(FormatState.Queued)))
        books.value = listOf(book("1", record(FormatState.Complete(400))), book("2", record(FormatState.Queued)))
        assertThat(done).isNull()

        books.value = listOf(book("1", record(FormatState.Complete(400))), book("2", record(FormatState.Complete(300))))
        assertThat(done.text).startsWith("2 books downloaded · ")

        manager.cancel(DoneNotificationId)
        books.value = books.value + book("3", record(FormatState.Complete(1), removing = true))
        assertThat(done).isNull()

        books.value = books.value + book("4", record(FormatState.Queued))
        books.value = books.value.dropLast(1) + book("4", record(FormatState.Complete(5)))
        assertThat(done.text).startsWith("1 book downloaded · ")
    }

    @Test
    fun `the queue is empty with what failed, which the notification counts`() = following {
        books.value = listOf(book("1", record(FormatState.Queued)), book("2", record(FormatState.Queued)))
        books.value = listOf(
            book("1", record(FormatState.Complete(400))),
            book("2", record(FormatState.Failed(DownloadFailure.Damaged, 0))),
        )

        assertThat(done.text).isEqualTo("1 book downloaded · 1 failed")
    }

    @Test
    fun `nothing for a batch of cancellations only`() = following {
        books.value = listOf(book("1", record(FormatState.Queued)), book("2", record(FormatState.Downloading(1, 9))))
        books.value = listOf(book("1", record(FormatState.Queued)))
        books.value = emptyList()

        assertThat(done).isNull()
    }

    /** They say what an account downloads, and their tap and button would act for the next one. */
    @Test
    fun `the profile closes - both notifications go, and none is posted after`() = following {
        queue.value = waitingForWifi(2)
        books.value = listOf(book("1", record(FormatState.Queued)))
        books.value = listOf(book("1", record(FormatState.Complete(400))))
        assertThat(waiting).isNotNull()
        assertThat(done).isNotNull()

        notices.close()
        assertThat(shadowOf(manager).allNotifications).isEmpty()

        // The queue is followed a moment longer, until the profile's scope ends.
        queue.value = QueueState(entry(bookId = BookId("1")), emptyList(), null, 5)
        queue.value = waitingForWifi(1)
        books.value = books.value + book("2", record(FormatState.Queued))
        books.value = books.value.dropLast(1) + book("2", record(FormatState.Complete(5)))

        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun `without the permission nothing is posted and nothing fails`() = following {
        shadowOf(manager).setNotificationsEnabled(false)

        queue.value = waitingForWifi(2)
        books.value = listOf(book("1", record(FormatState.Queued)))
        books.value = listOf(book("1", record(FormatState.Complete(400))))

        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }
}
