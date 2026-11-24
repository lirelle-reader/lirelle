package app.tfdn.lirelle.core.downloads.model

import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class DownloadedBookTest {
    private val asked = Instant.parse("2026-11-01T10:00:00Z")
    private val downloaded = Instant.parse("2026-11-02T10:00:00Z")
    private val opened = Instant.parse("2026-11-03T10:00:00Z")

    private fun record(format: DownloadFormat, state: FormatState) =
        FormatRecord(format, state, files = emptyList(), fileId = null, version = null, askedAt = asked)

    private fun book(vararg formats: FormatRecord, lastOpenedAt: Instant? = null) =
        DownloadedBook(BookId("1"), formats.toList(), downloaded, lastOpenedAt)

    @Test
    fun `a book is downloaded as soon as one format is complete`() {
        val waiting = record(DownloadFormat.Audiobook, FormatState.Waiting(WaitReason.NoNetwork, 10, 100))

        assertThat(book(waiting).isDownloaded).isFalse()
        assertThat(book().isDownloaded).isFalse()
        assertThat(book(waiting, record(DownloadFormat.Text, FormatState.Complete(5))).isDownloaded).isTrue()
    }

    @Test
    fun `the bytes on the device are what each format has there, complete or not`() {
        val book = book(
            record(DownloadFormat.Text, FormatState.Complete(1_000)),
            record(DownloadFormat.Audiobook, FormatState.Downloading(200, 5_000)),
            record(DownloadFormat.Narration, FormatState.Queued),
        )

        assertThat(book.bytesOnDevice).isEqualTo(1_200)
    }

    @Test
    fun `a stopped format still counts what it received`() {
        val waiting = record(DownloadFormat.Audiobook, FormatState.Waiting(WaitReason.UnmeteredOnly, 30, null))
        val failed = record(DownloadFormat.Text, FormatState.Failed(DownloadFailure.Damaged, 4))

        assertThat(book(waiting, failed).bytesOnDevice).isEqualTo(34)
    }

    @Test
    fun `the last activity is the last opening, or the download when it was never opened`() {
        assertThat(book().lastActivity).isEqualTo(downloaded)
        assertThat(book(lastOpenedAt = opened).lastActivity).isEqualTo(opened)
    }
}
