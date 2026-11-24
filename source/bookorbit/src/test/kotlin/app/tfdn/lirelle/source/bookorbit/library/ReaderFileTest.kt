package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The file the book reader opens, among the files the mapper says are the book's content. */
class ReaderFileTest {

    private val mapper = LibraryMapper("https://books.example.com")

    private fun book(vararg files: BookFileDto): BookDetail =
        mapper.detail(
            BookDetailDto(id = 5, addedAt = "2026-11-01T08:00:00.000Z", title = "Book 1", files = files.toList()),
            audioChapterCount = null,
        )

    @Test
    fun `the primary EPUB is the file to read`() {
        val book = book(BookFileDto(1, "epub", "content"), BookFileDto(2, "epub", "primary"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
    }

    @Test
    fun `a primary KEPUB is read too`() {
        assertThat(book(BookFileDto(1, "epub", "content"), BookFileDto(2, "kepub", "primary")).readerFile?.id)
            .isEqualTo(FileId("2"))
    }

    @Test
    fun `when the primary file cannot be read, the first EPUB is`() {
        val book = book(
            BookFileDto(1, "m4b", "primary"),
            BookFileDto(2, "pdf", "content"),
            BookFileDto(3, "epub", "content"),
            BookFileDto(4, "kepub", "content"),
        )

        assertThat(book.readerFile?.id).isEqualTo(FileId("3"))
    }

    @Test
    fun `a book without an EPUB has nothing to read`() {
        assertThat(book(BookFileDto(1, "pdf", "primary"), BookFileDto(2, "m4b", "content")).readerFile).isNull()
        assertThat(book().readerFile).isNull()
    }

    @Test
    fun `a file that is not content is never read`() {
        assertThat(book(BookFileDto(1, "epub", "sidecar")).readerFile).isNull()
    }

    @Test
    fun `a file carries its version and whether it has a narration`() {
        val book = book(
            BookFileDto(
                id = 1,
                format = "epub",
                role = "primary",
                contentVersion = "2026-11-11T13:40:59.633Z",
                mediaOverlay = MediaOverlayDto(available = true, durationSeconds = 60.0),
            ),
            BookFileDto(2, "epub", "content"),
        )

        assertThat(book.files[0].contentVersion).isEqualTo("2026-11-11T13:40:59.633Z")
        assertThat(book.files[0].hasReadAlong).isTrue()
        assertThat(book.files[1].contentVersion).isNull()
        assertThat(book.files[1].hasReadAlong).isFalse()
    }
}
