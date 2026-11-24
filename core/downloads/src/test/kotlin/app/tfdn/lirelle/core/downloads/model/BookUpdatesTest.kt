package app.tfdn.lirelle.core.downloads.model

import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class BookUpdatesTest {
    private val asked = Instant.parse("2026-11-01T10:00:00Z")
    private val complete = FormatState.Complete(1_000)

    private fun update(bytes: Long, state: FormatState? = null) =
        FormatUpdate(version = "v2", files = emptyList(), bytes = bytes, state = state)

    private fun record(format: DownloadFormat, state: FormatState, update: FormatUpdate? = null) =
        FormatRecord(format, state, emptyList(), fileId = null, version = "v1", askedAt = asked, update = update)

    private fun book(vararg formats: FormatRecord) =
        DownloadedBook(BookId("1"), formats.toList(), asked, lastOpenedAt = null)

    @Test
    fun `a record knows no update, is not gone and is not to delete until told`() {
        val book = book(record(DownloadFormat.Text, complete))

        assertThat(book.formats.single().update).isNull()
        assertThat(book.gone).isFalse()
        assertThat(book.deleteAtClose).isFalse()
        assertThat(book.hasUpdate).isFalse()
        assertThat(book.isUpdating).isFalse()
        assertThat(book.updateBytes).isEqualTo(0)
    }

    @Test
    fun `a complete format with an update that waits for the user has an update`() {
        val book = book(
            record(DownloadFormat.Text, complete),
            record(DownloadFormat.Audiobook, complete, update(500)),
        )

        assertThat(book.hasUpdate).isTrue()
        assertThat(book.isUpdating).isFalse()
    }

    @Test
    fun `an update of a format that is not complete does not count`() {
        val book = book(record(DownloadFormat.Text, FormatState.Downloading(1, 10), update(500)))

        assertThat(book.hasUpdate).isFalse()
    }

    @Test
    fun `the bytes of the update are those of the updates not started`() {
        val book = book(
            record(DownloadFormat.Text, complete, update(500)),
            record(DownloadFormat.Audiobook, complete, update(7_000)),
            record(DownloadFormat.Narration, complete, update(90_000, FormatState.Queued)),
        )

        assertThat(book.updateBytes).isEqualTo(7_500)
    }

    @Test
    fun `an update asked is updating, whatever it waits for`() {
        val running = listOf(
            FormatState.Queued,
            FormatState.Downloading(10, 100),
            FormatState.Waiting(WaitReason.NoNetwork, 10, 100),
            FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null),
        )

        running.forEach { state ->
            val book = book(record(DownloadFormat.Text, complete, update(500, state)))

            assertThat(book.isUpdating).isTrue()
            assertThat(book.hasUpdate).isFalse()
        }
    }

    @Test
    fun `an update that failed is not updating, and no longer waits for the user`() {
        val failed = FormatState.Failed(DownloadFailure.Damaged, 4)
        val book = book(record(DownloadFormat.Text, complete, update(500, failed)))

        assertThat(book.isUpdating).isFalse()
        assertThat(book.hasUpdate).isFalse()
        assertThat(book.updateBytes).isEqualTo(0)
    }
}
