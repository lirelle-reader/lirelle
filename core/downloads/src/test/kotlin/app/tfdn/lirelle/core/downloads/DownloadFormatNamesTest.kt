package app.tfdn.lirelle.core.downloads

import android.content.res.Resources
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The words for a set of formats: one phrase, whatever the order they come in; and those for pages. */
@RunWith(RobolectricTestRunner::class)
class DownloadFormatNamesTest {

    private val resources: Resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `one format is named alone`() {
        assertThat(resources.formatsName(listOf(Text))).isEqualTo("Text")
        assertThat(resources.formatsName(listOf(Audiobook))).isEqualTo("Audiobook")
        assertThat(resources.formatsName(listOf(Narration))).isEqualTo("Narration")
    }

    @Test
    fun `several formats are one phrase, worded for a reflowable text as it always was`() {
        assertThat(resources.formatsName(listOf(Audiobook, Text))).isEqualTo("Text and audiobook")
        assertThat(resources.formatsName(setOf(Text, Narration))).isEqualTo("Text and narration")
        assertThat(resources.formatsName(listOf(Audiobook, Narration))).isEqualTo("Audiobook and narration")
        assertThat(resources.formatsName(listOf(Narration, Audiobook, Text))).isEqualTo("Text, audiobook and narration")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the phrases in French, worded for a reflowable text as they always were`() {
        assertThat(resources.formatsName(listOf(Text))).isEqualTo("Texte")
        assertThat(resources.formatsName(listOf(Audiobook, Text))).isEqualTo("Texte et livre audio")
        assertThat(resources.formatsName(setOf(Text, Narration))).isEqualTo("Texte et narration")
        assertThat(resources.formatsName(listOf(Audiobook, Narration))).isEqualTo("Livre audio et narration")
        assertThat(resources.formatsName(listOf(Narration, Audiobook, Text)))
            .isEqualTo("Texte, livre audio et narration")
    }

    @Test
    fun `the text of a book read by pages is named by its file's format`() {
        val names = mapOf(
            TextName.Text to R.string.downloads_format_text,
            TextName.Pdf to R.string.downloads_format_pdf,
            TextName.Cbz to R.string.downloads_format_cbz,
            TextName.Cbr to R.string.downloads_format_cbr,
            TextName.Cb7 to R.string.downloads_format_cb7,
            TextName.Djvu to R.string.downloads_format_djvu,
        )
        val said = listOf("Text", "PDF", "CBZ", "CBR", "CB7", "DjVu")

        assertThat(names.keys).containsExactlyElementsIn(TextName.entries)
        names.forEach { (text, name) -> assertThat(nameRes(Text, text)).isEqualTo(name) }
        assertThat(TextName.entries.map { resources.formatsName(listOf(Text), it) }).isEqualTo(said)
    }

    @Test
    fun `the audiobook and the narration are named the same whatever the text is`() {
        TextName.entries.forEach { text ->
            assertThat(nameRes(Audiobook, text)).isEqualTo(R.string.downloads_format_audiobook)
            assertThat(nameRes(Narration, text)).isEqualTo(R.string.downloads_format_narration)
            assertThat(resources.formatsName(listOf(Audiobook), text)).isEqualTo("Audiobook")
            assertThat(resources.formatsName(listOf(Audiobook, Narration), text)).isEqualTo("Audiobook and narration")
        }
    }

    @Test
    fun `beside another format the text is named by its file too`() {
        assertThat(resources.formatsName(listOf(Text, Audiobook), TextName.Pdf)).isEqualTo("PDF and audiobook")
        assertThat(resources.formatsName(listOf(Text, Narration), TextName.Cbz)).isEqualTo("CBZ and narration")
        assertThat(resources.formatsName(listOf(Text, Audiobook, Narration), TextName.Djvu))
            .isEqualTo("DjVu, audiobook and narration")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `beside another format in French`() {
        assertThat(resources.formatsName(listOf(Text, Audiobook), TextName.Pdf)).isEqualTo("PDF et livre audio")
        assertThat(resources.formatsName(listOf(Text, Narration), TextName.Cbz)).isEqualTo("CBZ et narration")
        assertThat(resources.formatsName(listOf(Text, Audiobook, Narration), TextName.Djvu))
            .isEqualTo("DjVu, livre audio et narration")
    }

    @Test
    fun `the pages of a download are those it has, of those it is to have`() {
        assertThat(resources.pagesOf(PageCount(12, 48))).isEqualTo("12 pages of 48")
        assertThat(resources.pagesOf(PageCount(2, 48))).isEqualTo("2 pages of 48")
        assertThat(resources.pagesOf(PageCount(1, 1))).isEqualTo("1 page of 1")
    }

    @Test
    fun `the word follows the pages it has, not those it is to have`() {
        assertThat(resources.pagesOf(PageCount(1, 48))).isEqualTo("1 page of 48")
        assertThat(resources.pagesOf(PageCount(0, 48))).isEqualTo("0 pages of 48")
        assertThat(resources.pagesOf(PageCount(0, 1))).isEqualTo("0 pages of 1")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the pages in French`() {
        assertThat(resources.pagesOf(PageCount(12, 48))).isEqualTo("12 pages sur 48")
        assertThat(resources.pagesOf(PageCount(2, 48))).isEqualTo("2 pages sur 48")
        assertThat(resources.pagesOf(PageCount(1, 1))).isEqualTo("1 page sur 1")
        assertThat(resources.pagesOf(PageCount(2_000_000, 3_000_000))).isEqualTo("2000000 pages sur 3000000")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the word follows the pages it has in French, where none is said as one`() {
        assertThat(resources.pagesOf(PageCount(1, 48))).isEqualTo("1 page sur 48")
        assertThat(resources.pagesOf(PageCount(0, 48))).isEqualTo("0 page sur 48")
    }
}
