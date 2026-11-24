package app.tfdn.lirelle.core.downloads.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the text is named by, from the format of its file. */
class TextNameTest {

    @Test
    fun `a book read by pages is named by its format`() {
        assertThat(TextName.of("PDF")).isEqualTo(TextName.Pdf)
        assertThat(TextName.of("CBZ")).isEqualTo(TextName.Cbz)
        assertThat(TextName.of("CBR")).isEqualTo(TextName.Cbr)
        assertThat(TextName.of("CB7")).isEqualTo(TextName.Cb7)
        assertThat(TextName.of("DJVU")).isEqualTo(TextName.Djvu)
    }

    @Test
    fun `the case of the format does not matter`() {
        assertThat(TextName.of("pdf")).isEqualTo(TextName.Pdf)
        assertThat(TextName.of("Cbz")).isEqualTo(TextName.Cbz)
        assertThat(TextName.of("cb7")).isEqualTo(TextName.Cb7)
        assertThat(TextName.of("djvu")).isEqualTo(TextName.Djvu)
    }

    @Test
    fun `a reflowable format, an unknown one and none are the text`() {
        listOf("EPUB", "KEPUB", "MOBI", "AZW", "AZW3", "FB2", "epub", "M4B", "TXT", "", null).forEach {
            assertThat(TextName.of(it)).isEqualTo(TextName.Text)
        }
    }
}
