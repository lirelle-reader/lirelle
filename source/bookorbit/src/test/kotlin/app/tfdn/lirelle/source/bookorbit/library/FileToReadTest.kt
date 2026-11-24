package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A book has one file to read: the book reader's or the page reader's, never both. The mapper says which files
 * are content and which is primary (the server's roles), and what an account that may not download reads.
 */
class FileToReadTest {

    private val mapper = LibraryMapper("https://books.example.com")

    private fun book(vararg files: BookFileDto): BookDetail =
        mapper.detail(
            BookDetailDto(id = 5, addedAt = "2026-11-01T08:00:00.000Z", title = "Album 1", files = files.toList()),
            audioChapterCount = null,
        )

    @Test
    fun `a book with an EPUB and a primary CBZ is a comic, and its EPUB is not read`() {
        val book = book(BookFileDto(1, "epub", "content"), BookFileDto(2, "cbz", "primary"))

        assertThat(book.pagedFile?.id).isEqualTo(FileId("2"))
        assertThat(book.isPaged).isTrue()
        assertThat(book.readerFile).isNull()
        assertThat(book.readerFile(canDownload = false)).isNull()
        // BookOrbit says no reading direction: the comics reader's own default and the user's choice decide.
        assertThat(book.pageDirection).isNull()
    }

    @Test
    fun `a book with a primary EPUB and a CBZ beside is read as a book`() {
        val book = book(BookFileDto(1, "cbz", "content"), BookFileDto(2, "epub", "primary"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
        assertThat(book.pagedFile).isNull()
        assertThat(book.isPaged).isFalse()
    }

    @Test
    fun `a primary KEPUB is read as a book too`() {
        val book = book(BookFileDto(1, "cbz", "content"), BookFileDto(2, "kepub", "primary"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
        assertThat(book.isPaged).isFalse()
    }

    @Test
    fun `when the primary file cannot be read, an EPUB comes before a comic file`() {
        val book = book(
            BookFileDto(1, "m4b", "primary"),
            BookFileDto(2, "cbz", "content"),
            BookFileDto(3, "epub", "content"),
        )

        assertThat(book.readerFile?.id).isEqualTo(FileId("3"))
        assertThat(book.pagedFile).isNull()
    }

    @Test
    fun `without a primary file, an EPUB comes before a comic file`() {
        val book = book(BookFileDto(1, "cbz", "content"), BookFileDto(2, "epub", "content"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
        assertThat(book.isPaged).isFalse()
    }

    @Test
    fun `a primary file that is not read, with comic files only, leaves a comic file, CB7 and CBR alike`() {
        val cb7 = BookFileDto(2, "cb7", "content")
        val cbr = BookFileDto(3, "cbr", "content")
        val beside = book(BookFileDto(1, "txt", "primary"), cb7)
        val listened = book(BookFileDto(1, "m4b", "primary"), cbr, cb7)

        assertThat(beside.pagedFile?.id).isEqualTo(FileId("2"))
        assertThat(beside.pagedKind).isEqualTo(PagedKind.Comic)
        assertThat(beside.readerFile).isNull()
        assertThat(listened.pagedFile?.id).isEqualTo(FileId("3"))
        // By the order of the formats, not the server's order of the files: a CBR before a CB7.
        assertThat(book(BookFileDto(1, "m4b", "primary"), cb7, cbr).pagedFile?.id).isEqualTo(FileId("3"))
    }

    @Test
    fun `a CBZ alone is the comic to read, and still a text file`() {
        val book = book(BookFileDto(1, "cbz", "primary"))

        assertThat(book.readerFile).isNull()
        assertThat(book.pagedFile?.id).isEqualTo(FileId("1"))
        assertThat(book.pagedFile?.kind).isEqualTo(BookFileKind.Text)
        assertThat(book.isPaged).isTrue()
        assertThat(book.pagedKind).isEqualTo(PagedKind.Comic)
    }

    @Test
    fun `of two CBZ files, the primary one is read`() {
        val book = book(BookFileDto(1, "cbz", "content"), BookFileDto(2, "cbz", "primary"))

        assertThat(book.pagedFile?.id).isEqualTo(FileId("2"))
    }

    @Test
    fun `a comic with a read-along EPUB beside stays a comic for an account that may not download`() {
        val readAlong =
            BookFileDto(1, "epub", "content", mediaOverlay = MediaOverlayDto(available = true, durationSeconds = 60.0))
        val book = book(readAlong, BookFileDto(2, "epub", "content"), BookFileDto(3, "cbr", "primary"))

        assertThat(book.pagedFile?.id).isEqualTo(FileId("3"))
        assertThat(book.readerFile(canDownload = false)).isNull()
        assertThat(book.readerFile(canDownload = true)).isNull()
    }

    @Test
    fun `a book with nothing the app can read has neither file`() {
        val unreadable = listOf(
            book(BookFileDto(1, "m4b", "primary")),
            book(BookFileDto(1, "txt", "primary"), BookFileDto(2, "m4b", "content")),
            book(BookFileDto(1, "cbz", "sidecar")),
            book(BookFileDto(1, "pdf", "sidecar"), BookFileDto(2, null, "content")),
            book(),
        )

        unreadable.forEach { book ->
            assertThat(book.pagedFile).isNull()
            assertThat(book.readerFile).isNull()
            assertThat(book.isPaged).isFalse()
            assertThat(book.pagedKind).isNull()
        }
    }
}
