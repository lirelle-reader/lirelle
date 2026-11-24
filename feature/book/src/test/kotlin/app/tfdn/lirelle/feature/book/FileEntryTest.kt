package app.tfdn.lirelle.feature.book

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileEntryTest {

    private fun text(id: Int, format: String, primary: Boolean = false) =
        BookFile(FileId("$id"), BookFileKind.Text, format, 1_000, primary, null, null)

    private fun track(id: Int, format: String = "MP3", primary: Boolean = false) =
        BookFile(FileId("$id"), BookFileKind.Audio, format, 1_000, primary, 60, null)

    private fun formats(files: List<BookFile>) = fileEntries(files).map { it.format }

    @Test
    fun `the primary file comes first, the others keep their order`() {
        val entries = fileEntries(listOf(text(1, "PDF"), track(2, "M4B"), text(3, "MOBI"), text(4, "EPUB", true)))

        assertThat(entries.map { it.format }).containsExactly("EPUB", "PDF", "M4B", "MOBI").inOrder()
        assertThat(entries.map { it.isPrimary }).containsExactly(true, false, false, false).inOrder()
    }

    @Test
    fun `the audio row that holds the primary file comes first`() {
        val files = listOf(text(1, "EPUB"), track(2), track(3, primary = true), text(4, "PDF"), track(5))

        val entries = fileEntries(files)

        assertThat(entries.map { it.format }).containsExactly("MP3", "EPUB", "PDF").inOrder()
        assertThat(entries.first().fileCount).isEqualTo(3)
        assertThat(entries.first().isPrimary).isTrue()
    }

    @Test
    fun `a primary file already first changes nothing`() {
        assertThat(formats(listOf(text(1, "EPUB", true), track(2, "M4B"), text(3, "PDF"))))
            .containsExactly("EPUB", "M4B", "PDF").inOrder()
    }

    @Test
    fun `without a primary file the order of the server is kept`() {
        assertThat(formats(listOf(text(1, "PDF"), track(2, "M4B"), text(3, "EPUB"))))
            .containsExactly("PDF", "M4B", "EPUB").inOrder()
    }
}
