package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.service.FinishedBook
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The row of the audio end panel that says what the device keeps of the book, and deletes it. */
class PlayerEndDeviceTest {

    private val whole = DeviceCopy(listOf(DownloadFormat.Text, DownloadFormat.Audiobook), 2_000, deleting = false)

    /** The book 42 listened to its end, which the device keeps and the player holds. */
    private fun TestScope.finished(): PlayerHarness = PlayerHarness(this).apply {
        downloads.has(BookId("42"), DownloadFormat.Audiobook)
        downloads.has(BookId("42"), DownloadFormat.Text)
        deletions.held += BookId("42")
        watched()
        show(SessionBooks.at())
        show(SessionBooks.finished())
    }

    private val PlayerHarness.device: DeviceCopy? get() = controller.state.value.finished?.device

    @Test
    fun `a book the device does not keep has no row`() = runTest {
        val harness = PlayerHarness(this).apply {
            watched()
            show(SessionBooks.finished())
        }

        assertThat(harness.controller.state.value.finished).isNotNull()
        assertThat(harness.device).isNull()

        harness.controller.deleteFromDevice()
        assertThat(harness.deletions.asked).isEmpty()
    }

    @Test
    fun `the panel says what the device keeps of the book`() = runTest {
        assertThat(finished().device).isEqualTo(whole)
    }

    @Test
    fun `delete is asked once and the row says so, undo takes it back`() = runTest {
        val harness = finished()

        harness.controller.deleteFromDevice()
        harness.controller.deleteFromDevice()
        assertThat(harness.device).isEqualTo(whole.copy(deleting = true))
        assertThat(harness.deletions.asked).containsExactly("delete 42")

        harness.controller.keepOnDevice()
        assertThat(harness.device).isEqualTo(whole)
        assertThat(harness.deletions.asked).containsExactly("delete 42", "keep 42").inOrder()
    }

    @Test
    fun `a book played from the server goes at once, and the row with it`() = runTest {
        val harness = finished().apply { deletions.held.clear() }

        harness.controller.deleteFromDevice()

        assertThat(harness.controller.state.value.finished).isNotNull()
        assertThat(harness.device).isNull()
    }

    @Test
    fun `the row is the one of the book the session shows`() = runTest {
        val harness = finished()
        val other = SessionBooks.book.copy(id = BookId("43"))
        harness.downloads.has(BookId("43"), DownloadFormat.Audiobook)

        harness.show(SessionBooks.at(extras = SessionBooks.finished().extras.copy(book = other)))

        assertThat(harness.device).isEqualTo(DeviceCopy(listOf(DownloadFormat.Audiobook), 1_000, deleting = false))
        harness.controller.deleteFromDevice()
        assertThat(harness.deletions.asked).containsExactly("delete 43")
    }

    @Test
    fun `a narration is the reader's to end, the player deletes nothing for it`() = runTest {
        val harness = finished()

        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(finished = FinishedBook(null))))
        harness.controller.deleteFromDevice()

        assertThat(harness.controller.state.value.finished).isNull()
        assertThat(harness.deletions.asked).isEmpty()
    }
}
