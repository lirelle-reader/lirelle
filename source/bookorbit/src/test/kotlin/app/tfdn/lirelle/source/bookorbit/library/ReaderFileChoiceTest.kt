package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The file the reader opens for an account that may not download: BookOrbit refuses it a read-along EPUB's text,
 * so the mapper hands over the book's plain EPUB in its place.
 */
class ReaderFileChoiceTest {

    private val mapper = LibraryMapper("https://books.example.com")

    private fun book(vararg files: BookFileDto): BookDetail =
        mapper.detail(
            BookDetailDto(id = 5, addedAt = "2026-11-01T08:00:00.000Z", title = "Book 1", files = files.toList()),
            audioChapterCount = null,
        )

    private fun readAlong(id: Long, role: String = "primary") =
        BookFileDto(id, "epub", role, mediaOverlay = MediaOverlayDto(available = true, durationSeconds = 60.0))

    @Test
    fun `an account that may download reads the read-along file, as the property says`() {
        val book = book(readAlong(1), BookFileDto(2, "epub", "content"))

        assertThat(book.readerFile(canDownload = true)?.id).isEqualTo(FileId("1"))
        assertThat(book.readerFile?.id).isEqualTo(FileId("1"))
    }

    @Test
    fun `an account that may not reads the first plain EPUB or KEPUB the book has`() {
        val book = book(
            BookFileDto(1, "m4b", "content"),
            readAlong(2),
            readAlong(3, role = "content"),
            BookFileDto(4, "kepub", "content"),
            BookFileDto(5, "epub", "content"),
        )

        assertThat(book.readerFileWithoutDownload?.id).isEqualTo(FileId("4"))
        assertThat(book.readerFile(canDownload = false)?.id).isEqualTo(FileId("4"))
        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
    }

    @Test
    fun `an account that may not keeps the read-along file of a book that has no plain EPUB`() {
        val book = book(readAlong(1), BookFileDto(2, "pdf", "content"), BookFileDto(3, "m4b", "content"))

        assertThat(book.readerFileWithoutDownload).isNull()
        assertThat(book.readerFile(canDownload = false)?.id).isEqualTo(FileId("1"))
    }

    @Test
    fun `a plain book is read from the same file whatever the account may do`() {
        val book = book(BookFileDto(1, "epub", "content"), BookFileDto(2, "epub", "primary"), readAlong(3, "content"))

        assertThat(book.readerFileWithoutDownload).isNull()
        assertThat(book.readerFile(canDownload = false)?.id).isEqualTo(FileId("2"))
        assertThat(book.readerFile(canDownload = true)?.id).isEqualTo(FileId("2"))
        assertThat(book(BookFileDto(1, "pdf", "primary")).readerFile(canDownload = false)).isNull()
    }

    @Test
    fun `a plain EPUB that is not content never stands for the read-along file`() {
        val book = book(readAlong(1), BookFileDto(2, "epub", "sidecar"))

        assertThat(book.readerFileWithoutDownload).isNull()
        assertThat(book.readerFile(canDownload = false)?.id).isEqualTo(FileId("1"))
    }
}
