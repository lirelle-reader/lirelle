package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfferedFormatTest {

    @Test
    fun `a text-only book offers its text, with the size of the EPUB`() {
        val book = Books.book(Books.epub(11, sizeBytes = 1_200_000))

        assertThat(book.offeredFormats()).containsExactly(OfferedFormat(DownloadFormat.Text, FileId("11"), 1_200_000))
    }

    @Test
    fun `the text offered is the file the reader opens`() {
        val book = Books.book(Books.epub(11, primary = false), Books.epub(12, sizeBytes = 900_000, primary = true))

        assertThat(book.offeredFormats().single().fileId).isEqualTo(FileId("12"))
    }

    @Test
    fun `a book read from a single file of any format offers that file as its text`() {
        listOf("EPUB", "KEPUB", "MOBI", "AZW3", "AZW", "FB2").forEach { format ->
            val book = Books.book(Books.epub(11, sizeBytes = 900_000, format = format))

            assertThat(book.offeredFormats()).containsExactly(OfferedFormat(DownloadFormat.Text, FileId("11"), 900_000))
            assertThat(book.textFile?.id).isEqualTo(FileId("11"))
        }
    }

    @Test
    fun `a PDF offers its file as its text, of its exact size, named as a PDF`() {
        val book = Books.book(Books.epub(11, sizeBytes = 900_000, format = "PDF"))

        assertThat(book.offeredFormats())
            .containsExactly(OfferedFormat(DownloadFormat.Text, FileId("11"), 900_000, textName = TextName.Pdf))
        assertThat(book.textFile?.id).isEqualTo(FileId("11"))
    }

    @Test
    fun `the text offered is the file read, not the EPUB beside it`() {
        val beside = Books.book(Books.epub(11, format = "MOBI"), Books.epub(12, primary = false))
        val pdf = Books.book(Books.epub(11, format = "PDF"), Books.epub(12, format = "CBZ", primary = false))

        assertThat(beside.offeredFormats().single().fileId).isEqualTo(FileId("11"))
        assertThat(pdf.offeredFormats().single().fileId).isEqualTo(FileId("11"))
    }

    @Test
    fun `a book served page by page offers its pages as its text, about as large as its file`() {
        val names = mapOf(
            "CBZ" to TextName.Cbz, "CBR" to TextName.Cbr, "CB7" to TextName.Cb7, "DJVU" to TextName.Djvu,
        )
        names.forEach { (format, name) ->
            val book = Books.book(Books.epub(11, sizeBytes = 48_000_000, format = format))

            assertThat(book.offeredFormats()).containsExactly(
                OfferedFormat(DownloadFormat.Text, FileId("11"), 48_000_000, estimated = true, textName = name),
            )
            assertThat(book.textFile?.id).isEqualTo(FileId("11"))
        }
    }

    @Test
    fun `an album whose file has no size known offers its pages with none`() {
        val book = Books.book(Books.epub(11, sizeBytes = null, format = "cbz"))

        assertThat(book.offeredFormats())
            .containsExactly(OfferedFormat(DownloadFormat.Text, FileId("11"), null, textName = TextName.Cbz))
    }

    @Test
    fun `a comic with an audiobook offers both`() {
        val book = Books.book(Books.epub(11, sizeBytes = 5_000_000, format = "CBR"), Books.audio(21, 27_000_000))

        assertThat(book.offeredFormats().map { it.format })
            .containsExactly(DownloadFormat.Text, DownloadFormat.Audiobook).inOrder()
    }

    @Test
    fun `a PDF with an audiobook offers both, and no narration`() {
        val book = Books.book(Books.epub(11, sizeBytes = 5_000_000, format = "PDF"), Books.audio(21, 27_000_000))

        assertThat(book.offeredFormats()).containsExactly(
            OfferedFormat(DownloadFormat.Text, FileId("11"), 5_000_000, textName = TextName.Pdf),
            OfferedFormat(DownloadFormat.Audiobook, null, 27_000_000),
        ).inOrder()
    }

    @Test
    fun `an audio-only book offers its audiobook, with the sum of its files and its chapters`() {
        val book = Books.book(Books.audio(21, 27_000_000, chapters = 40), Books.audio(22, 14_000_000))

        assertThat(book.offeredFormats())
            .containsExactly(OfferedFormat(DownloadFormat.Audiobook, null, 41_000_000, chapterCount = 40))
    }

    @Test
    fun `an audiobook whose files have no size has none`() {
        val book = Books.book(Books.audio(21, null), Books.audio(22, null))

        assertThat(book.offeredFormats()).containsExactly(OfferedFormat(DownloadFormat.Audiobook, null, null))
    }

    @Test
    fun `a book with a text and an audiobook offers both, the text first`() {
        val book = Books.book(Books.audio(21, 606_000_000, chapters = 44, format = "M4B"), Books.epub(11))

        assertThat(book.offeredFormats()).containsExactly(
            OfferedFormat(DownloadFormat.Text, FileId("11"), 1_200_000),
            OfferedFormat(DownloadFormat.Audiobook, null, 606_000_000, chapterCount = 44),
        ).inOrder()
    }

    @Test
    fun `a read-along book with an audiobook offers the three formats`() {
        val book = Books.book(Books.readAlong(31, 98_700_000), Books.audio(21, 310_000_000, chapters = 24))

        assertThat(book.offeredFormats()).containsExactly(
            OfferedFormat(DownloadFormat.Text, FileId("31"), null),
            OfferedFormat(DownloadFormat.Audiobook, null, 310_000_000, chapterCount = 24),
            OfferedFormat(DownloadFormat.Narration, FileId("31"), 98_700_000),
        ).inOrder()
    }

    @Test
    fun `a read-along-only book offers its text, of unknown size, and its narration`() {
        val book = Books.book(Books.readAlong(31, 98_700_000))

        assertThat(book.offeredFormats()).containsExactly(
            OfferedFormat(DownloadFormat.Text, FileId("31"), null),
            OfferedFormat(DownloadFormat.Narration, FileId("31"), 98_700_000),
        ).inOrder()
    }

    @Test
    fun `the text of a read-along file is about as large as the plain EPUB of the same book`() {
        val book = Books.book(Books.readAlong(31, 98_700_000), Books.epub(11, sizeBytes = 2_800_000, primary = false))

        assertThat(book.offeredFormats()).containsExactly(
            OfferedFormat(DownloadFormat.Text, FileId("31"), 2_800_000, estimated = true),
            OfferedFormat(DownloadFormat.Narration, FileId("31"), 98_700_000),
        ).inOrder()
    }

    @Test
    fun `a plain EPUB without a size, or a file the reader does not open, estimates nothing`() {
        val unsized = Books.book(Books.readAlong(31), Books.epub(11, sizeBytes = null, primary = false))
        val pdf = Books.book(Books.readAlong(31), Books.epub(11, primary = false, format = "PDF"))

        assertThat(unsized.offeredFormats().first()).isEqualTo(OfferedFormat(DownloadFormat.Text, FileId("31"), null))
        assertThat(pdf.offeredFormats().first()).isEqualTo(OfferedFormat(DownloadFormat.Text, FileId("31"), null))
    }

    @Test
    fun `the size of a plain EPUB the reader opens is not an estimate`() {
        val book = Books.book(Books.epub(11, sizeBytes = 1_200_000))

        assertThat(book.offeredFormats().single().estimated).isFalse()
    }

    @Test
    fun `beside a plain EPUB the reader opens, the narration is that of the read-along file`() {
        val book = Books.book(Books.epub(11), Books.readAlong(31, primary = false))

        assertThat(book.offeredFormats()).containsExactly(
            OfferedFormat(DownloadFormat.Text, FileId("11"), 1_200_000),
            OfferedFormat(DownloadFormat.Narration, FileId("31"), 98_700_000),
        ).inOrder()
    }
}
