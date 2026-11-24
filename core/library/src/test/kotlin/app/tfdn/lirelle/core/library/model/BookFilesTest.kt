package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The app's rules on a book's files, on books built as a source hands them over: which file a reader opens, by
 * pages or not. Which files are content, which is primary and what an account that may not download reads are the
 * source's answers, carried by the model.
 */
class BookFilesTest {

    private fun file(id: Int, format: String, primary: Boolean = false, kind: BookFileKind = BookFileKind.Text) =
        BookFile(FileId("$id"), kind, format, sizeBytes = null, primary, narrationSeconds = null, chapterCount = null)

    private fun readAlong(id: Int, primary: Boolean = false) = file(id, "EPUB", primary, BookFileKind.ReadAlongEpub)

    private fun book(vararg files: BookFile, withoutDownload: BookFile? = null) = BookDetail(
        id = BookId("5"),
        title = "Book 1",
        subtitle = null,
        authors = emptyList(),
        series = null,
        coverUrl = null,
        coverShape = CoverShape.Portrait,
        media = MediaProfile.TextOnly,
        description = null,
        genres = emptyList(),
        rating = null,
        pageCount = null,
        publishedYear = null,
        publisher = null,
        language = null,
        isbn = null,
        libraryName = "Library",
        addedAt = null,
        narrators = emptyList(),
        collections = emptyList(),
        files = files.toList(),
        readState = null,
        startedAt = null,
        readerFileWithoutDownload = withoutDownload,
    )

    @Test
    fun `the primary file is read when the app reads its format`() {
        val book = book(file(1, "EPUB"), file(2, "MOBI", primary = true))

        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
        assertThat(book.pagedFile).isNull()
    }

    @Test
    fun `without a primary file it reads, the formats come in the app's order, the server's within a format`() {
        val book = book(file(1, "M4B", primary = true, kind = BookFileKind.Audio), file(2, "PDF"), file(3, "FB2"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("3"))
        assertThat(book(file(1, "CBR"), file(2, "PDF"), file(3, "PDF")).pagedFile?.id).isEqualTo(FileId("2"))
    }

    @Test
    fun `a book has one file to read, in one reader or the other`() {
        val comic = book(file(1, "EPUB"), file(2, "CBZ", primary = true))
        val text = book(file(1, "CBZ"), file(2, "KEPUB", primary = true))

        assertThat(comic.readerFile).isNull()
        assertThat(comic.pagedFile?.id).isEqualTo(FileId("2"))
        assertThat(comic.pagedKind).isEqualTo(PagedKind.Comic)
        assertThat(comic.isPaged).isTrue()
        assertThat(text.readerFile?.id).isEqualTo(FileId("2"))
        assertThat(text.pagedFile).isNull()
        assertThat(text.isPaged).isFalse()
    }

    @Test
    fun `a book with nothing the app reads has neither file`() {
        listOf(book(), book(file(1, "TXT", primary = true)), book(file(1, "M4B", kind = BookFileKind.Audio)))
            .forEach { book ->
                assertThat(book.readerFile).isNull()
                assertThat(book.pagedFile).isNull()
                assertThat(book.pagedKind).isNull()
            }
    }

    @Test
    fun `an account that may download reads the reader file`() {
        val book = book(readAlong(1, primary = true), file(2, "EPUB"), withoutDownload = file(2, "EPUB"))

        assertThat(book.readerFile(canDownload = true)?.id).isEqualTo(FileId("1"))
        assertThat(book.readerFile?.id).isEqualTo(FileId("1"))
    }

    @Test
    fun `an account that may not reads the file the source chose for it`() {
        val book = book(readAlong(1, primary = true), file(2, "EPUB"), withoutDownload = file(2, "EPUB"))

        assertThat(book.readerFile(canDownload = false)?.id).isEqualTo(FileId("2"))
    }

    @Test
    fun `an account that may not reads the reader file when the source chose none for it`() {
        val book = book(readAlong(1, primary = true), file(2, "PDF"))

        assertThat(book.readerFile(canDownload = false)?.id).isEqualTo(FileId("1"))
        assertThat(book(file(1, "PDF", primary = true)).readerFile(canDownload = false)).isNull()
    }

    @Test
    fun `an EPUB or a KEPUB is an EPUB, and a read-along one says so`() {
        assertThat(file(1, "EPUB").isEpub).isTrue()
        assertThat(file(1, "KEPUB").isEpub).isTrue()
        assertThat(file(1, "MOBI").isEpub).isFalse()
        assertThat(readAlong(1).hasReadAlong).isTrue()
        assertThat(file(1, "EPUB").hasReadAlong).isFalse()
    }
}
