package app.tfdn.lirelle.core.downloads.model

import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** What the end panels say the device keeps of a book. */
class DeviceCopyTest {

    private fun record(format: DownloadFormat, state: FormatState, removing: Boolean = false) =
        FormatRecord(format, state, emptyList(), null, null, Instant.EPOCH, removing = removing)

    private fun book(vararg formats: FormatRecord, deleteAtClose: Boolean = false) =
        DownloadedBook(BookId("7"), formats.toList(), Instant.EPOCH, lastOpenedAt = null, deleteAtClose = deleteAtClose)

    @Test
    fun `a book with nothing whole has no copy`() {
        val fetching = book(record(DownloadFormat.Text, FormatState.Downloading(300, 1_000)))

        assertThat(fetching.deviceCopy()).isNull()
        assertThat(book().deviceCopy()).isNull()
    }

    @Test
    fun `the whole formats, in their order, with everything the book has on the device`() {
        val kept = book(
            record(DownloadFormat.Audiobook, FormatState.Complete(900)),
            record(DownloadFormat.Narration, FormatState.Downloading(50, 400)),
            record(DownloadFormat.Text, FormatState.Complete(100)),
        )

        val formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
        assertThat(kept.deviceCopy()).isEqualTo(DeviceCopy(formats, bytes = 1_050, deleting = false))
    }

    @Test
    fun `a format being deleted is no longer part of the copy`() {
        val kept = book(
            record(DownloadFormat.Text, FormatState.Complete(100)),
            record(DownloadFormat.Audiobook, FormatState.Complete(900), removing = true),
        )

        assertThat(kept.deviceCopy()).isEqualTo(DeviceCopy(listOf(DownloadFormat.Text), bytes = 100, deleting = false))
        assertThat(book(record(DownloadFormat.Text, FormatState.Complete(100), removing = true)).deviceCopy()).isNull()
    }

    @Test
    fun `the copy says what its text is named by, of the text that is whole`() {
        fun text(name: TextName, state: FormatState = FormatState.Complete(100)) =
            record(DownloadFormat.Text, state).copy(textName = name)
        val audiobook = record(DownloadFormat.Audiobook, FormatState.Complete(900))

        assertThat(book(text(TextName.Cbz)).deviceCopy()?.textName).isEqualTo(TextName.Cbz)
        assertThat(book(audiobook, text(TextName.Pdf)).deviceCopy()?.textName).isEqualTo(TextName.Pdf)
        assertThat(book(text(TextName.Text)).deviceCopy()?.textName).isEqualTo(TextName.Text)
        // A text that is not whole is not part of the copy: its name is not either.
        val fetching = book(audiobook, text(TextName.Cbz, FormatState.Downloading(1, 2)))
        assertThat(fetching.deviceCopy()).isEqualTo(DeviceCopy(listOf(DownloadFormat.Audiobook), 901, false))
    }

    @Test
    fun `a book that goes when it closes says so`() {
        val flagged = book(record(DownloadFormat.Text, FormatState.Complete(100)), deleteAtClose = true)

        assertThat(flagged.deviceCopy()?.deleting).isTrue()
    }
}
