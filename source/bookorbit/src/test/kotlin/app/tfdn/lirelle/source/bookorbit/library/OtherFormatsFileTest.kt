package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayDto
import app.tfdn.lirelle.source.bookorbit.network.fileIdOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The file to read among every text format: the reflowable ones in the book reader, the paged ones by pages. */
class OtherFormatsFileTest {

    private val mapper = LibraryMapper("https://books.example.com")

    private fun book(vararg files: BookFileDto): BookDetail =
        mapper.detail(
            BookDetailDto(id = 5, addedAt = "2026-11-01T08:00:00.000Z", title = "Book 1", files = files.toList()),
            audioChapterCount = null,
        )

    private val audio = BookFileDto(1, "m4b", "primary")

    @Test
    fun `each reflowable format alone is read in the book reader`() {
        listOf("epub", "kepub", "mobi", "azw3", "azw", "fb2").forEach { format ->
            val books = listOf(book(BookFileDto(1, format, "primary")), book(BookFileDto(1, format, "content")))

            books.forEach { book ->
                assertThat(book.readerFile?.id).isEqualTo(FileId("1"))
                assertThat(book.readerFile(canDownload = false)?.id).isEqualTo(FileId("1"))
                assertThat(book.pagedFile).isNull()
                assertThat(book.pagedKind).isNull()
                assertThat(book.isPaged).isFalse()
            }
        }
    }

    @Test
    fun `each paged format alone is read by pages, with its kind`() {
        val kinds = mapOf(
            "pdf" to PagedKind.Pdf,
            "cbz" to PagedKind.Comic,
            "cbr" to PagedKind.Comic,
            "cb7" to PagedKind.Comic,
            "djvu" to PagedKind.Djvu,
        )

        kinds.forEach { (format, kind) ->
            val books = listOf(book(BookFileDto(1, format, "primary")), book(BookFileDto(1, format, "content")))

            books.forEach { book ->
                assertThat(book.pagedFile?.id).isEqualTo(FileId("1"))
                assertThat(book.pagedKind).isEqualTo(kind)
                assertThat(book.isPaged).isTrue()
                assertThat(book.readerFile).isNull()
                assertThat(book.readerFile(canDownload = false)).isNull()
            }
        }
    }

    @Test
    fun `a primary PDF beside an EPUB is the file read, by pages`() {
        val book = book(BookFileDto(1, "epub", "content"), BookFileDto(2, "pdf", "primary"))

        assertThat(book.pagedFile?.id).isEqualTo(FileId("2"))
        assertThat(book.pagedKind).isEqualTo(PagedKind.Pdf)
        assertThat(book.readerFile).isNull()
    }

    @Test
    fun `a primary EPUB beside a MOBI and a PDF is the file read`() {
        val book =
            book(BookFileDto(1, "mobi", "content"), BookFileDto(2, "pdf", "content"), BookFileDto(3, "epub", "primary"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("3"))
        assertThat(book.pagedFile).isNull()
        assertThat(book.pagedKind).isNull()
    }

    @Test
    fun `a primary MOBI beside an EPUB is the file read, in the book reader`() {
        val book = book(BookFileDto(1, "epub", "content"), BookFileDto(2, "mobi", "primary"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
        assertThat(book.readerFile?.isEpub).isFalse()
        assertThat(book.isPaged).isFalse()
    }

    @Test
    fun `a primary audio file beside a PDF and a MOBI leaves the MOBI`() {
        val book = book(audio, BookFileDto(2, "pdf", "content"), BookFileDto(3, "mobi", "content"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("3"))
        assertThat(book.pagedFile).isNull()
    }

    @Test
    fun `a primary audio file beside a CBZ and a PDF leaves the PDF`() {
        val book = book(audio, BookFileDto(2, "cbz", "content"), BookFileDto(3, "pdf", "content"))

        assertThat(book.pagedFile?.id).isEqualTo(FileId("3"))
        assertThat(book.pagedKind).isEqualTo(PagedKind.Pdf)
        assertThat(book.readerFile).isNull()
    }

    @Test
    fun `without a primary file the app reads, the formats come in the app's order`() {
        val formats = listOf("djvu", "cb7", "cbr", "cbz", "pdf", "fb2", "azw", "azw3", "mobi", "kepub", "epub")
        var left = formats.mapIndexed { index, format -> BookFileDto(index + 2L, format, "content") }
        val chosen = mutableListOf<String>()

        // Each file chosen is taken away: the next of the order is then the one read.
        while (left.isNotEmpty()) {
            val book = book(audio, *left.toTypedArray())
            val read = checkNotNull(book.readerFile ?: book.pagedFile)
            assertThat(book.readerFile == null).isNotEqualTo(book.pagedFile == null)
            chosen += read.format
            left = left.filter { fileIdOf(it.id) != read.id }
        }

        assertThat(chosen)
            .containsExactly("EPUB", "KEPUB", "MOBI", "AZW3", "AZW", "FB2", "PDF", "CBZ", "CBR", "CB7", "DJVU")
            .inOrder()
    }

    @Test
    fun `of two files of a format, the server's first is read`() {
        val book = book(audio, BookFileDto(2, "mobi", "content"), BookFileDto(3, "mobi", "content"))

        assertThat(book.readerFile?.id).isEqualTo(FileId("2"))
    }

    @Test
    fun `the read-along rule concerns EPUBs only`() {
        val readAlong =
            BookFileDto(2, "epub", "content", mediaOverlay = MediaOverlayDto(available = true, durationSeconds = 60.0))
        val mobi = book(BookFileDto(1, "mobi", "primary"), readAlong, BookFileDto(3, "epub", "content"))
        val narrated = book(BookFileDto(1, "mobi", "content"), readAlong.copy(role = "primary"))

        assertThat(mobi.readerFile(canDownload = false)?.id).isEqualTo(FileId("1"))
        // A MOBI is not the plain EPUB that stands for a read-along one.
        assertThat(narrated.readerFile(canDownload = false)?.id).isEqualTo(FileId("2"))
        assertThat(narrated.readerFile(canDownload = true)?.id).isEqualTo(FileId("2"))
    }

    @Test
    fun `an EPUB or a KEPUB is an EPUB, no other format is`() {
        fun isEpub(format: String) = book(BookFileDto(1, format, "primary")).files.single().isEpub

        assertThat(isEpub("epub")).isTrue()
        assertThat(isEpub("kepub")).isTrue()
        listOf("mobi", "azw3", "azw", "fb2", "pdf", "cbz", "djvu", "m4b").forEach { assertThat(isEpub(it)).isFalse() }
    }
}
