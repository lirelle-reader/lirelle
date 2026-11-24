package app.tfdn.lirelle.feature.book

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a book's page opens, with the server and without it, for each kind of file to read. */
class OpeningsTest {

    private val comic = sampleComicDetail()

    /** The comic's book, its one file in another [format]. */
    private fun paged(format: String): BookDetail = comic.copy(files = comic.files.map { it.copy(format = format) })

    private fun text(format: String): BookDetail {
        val book = sampleBookDetail(media = MediaProfile.TextOnly)
        return book.copy(files = listOf(book.files.first().copy(format = format, isPrimary = true)))
    }

    @Test
    fun `with the server every book that has a file to read is read`() {
        listOf(paged("PDF"), paged("CBZ"), paged("DJVU"), text("EPUB"), text("MOBI")).forEach { book ->
            assertThat(Openings(book, kept = null).read).isTrue()
        }
    }

    @Test
    fun `a kept PDF is read on a page built without the server`() {
        val pdf = paged("PDF")

        assertThat(Openings(pdf, kept = setOf(DownloadFormat.Text)).read).isTrue()
        assertThat(Openings(pdf, kept = emptySet()).read).isFalse()
        assertThat(Openings(pdf, kept = setOf(DownloadFormat.Audiobook)).read).isFalse()
    }

    @Test
    fun `a comic and a DjVu are read without the server only when their pages are kept`() {
        listOf("CBZ", "CBR", "CB7", "DJVU").forEach { format ->
            val book = paged(format)

            assertThat(Openings(book, kept = emptySet()).read).isFalse()
            // The audiobook of the same book may be kept without its pages.
            assertThat(Openings(book, kept = setOf(DownloadFormat.Audiobook)).read).isFalse()
            assertThat(Openings(book, kept = setOf(DownloadFormat.Text)).read).isTrue()
        }
    }

    @Test
    fun `a kept Kindle book is read without the server, as a kept EPUB is`() {
        listOf("EPUB", "MOBI", "AZW", "AZW3", "FB2").forEach { format ->
            val book = text(format)

            assertThat(Openings(book, kept = setOf(DownloadFormat.Text)).read).isTrue()
            assertThat(Openings(book, kept = emptySet()).read).isFalse()
        }
    }
}
