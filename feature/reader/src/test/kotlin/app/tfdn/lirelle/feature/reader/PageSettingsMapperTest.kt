package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.feature.reader.bridge.PageAlign
import app.tfdn.lirelle.feature.reader.bridge.PageBrightnessEdge
import app.tfdn.lirelle.feature.reader.bridge.PageFlow
import app.tfdn.lirelle.feature.reader.bridge.PageBlend
import app.tfdn.lirelle.feature.reader.bridge.PageFont
import app.tfdn.lirelle.feature.reader.bridge.PageNoteLabels
import app.tfdn.lirelle.feature.reader.bridge.PageSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageSettingsMapperTest {
    private val light = ReaderTheme.Light.colours(systemDark = false)

    private fun page(settings: ReaderSettings, twoPages: Boolean = false) =
        settings.toPageSettings(light, twoPages, EnglishNoteLabels)

    @Test
    fun `the default settings in the light theme`() {
        assertThat(page(ReaderSettings())).isEqualTo(
            PageSettings(
                background = "#fbf8f1",
                foreground = "#1b1e2e",
                link = "#1d4fa8",
                dark = false,
                font = PageFont.Book,
                fontSizePx = 18,
                lineHeight = 1.5,
                marginPercent = 7.0,
                align = PageAlign.Book,
                hyphenate = true,
                flow = PageFlow.Paginated,
                maxColumns = 1,
                brightnessEdge = PageBrightnessEdge.Left,
                highlightBlend = PageBlend.Multiply,
                highlightOpacity = light.highlightOpacity,
                searchColor = light.searchColour.toCssHex(),
                footnotePanel = true,
                noteLabels = PageNoteLabels(goTo = "Go to the note", title = "Note"),
            ),
        )
    }

    @Test
    fun `the colours are those of the theme given`() {
        val black = ReaderTheme.Black.colours(systemDark = false)

        val page = ReaderSettings().toPageSettings(black, twoPages = false, noteLabels = EnglishNoteLabels)

        assertThat(page.background).isEqualTo("#000000")
        assertThat(page.foreground).isEqualTo(black.foreground.toCssHex())
        assertThat(page.link).isEqualTo(black.link.toCssHex())
        assertThat(page.dark).isTrue()
    }

    @Test
    fun `every choice has its word for the page`() {
        assertThat(ReaderFont.entries.map { page(ReaderSettings(font = it)).font })
            .containsExactly(PageFont.Book, PageFont.Serif, PageFont.Sans, PageFont.Dyslexic).inOrder()
        assertThat(ReaderAlign.entries.map { page(ReaderSettings(align = it)).align })
            .containsExactly(PageAlign.Book, PageAlign.Justify, PageAlign.Start).inOrder()
        assertThat(ReaderFlow.entries.map { page(ReaderSettings(flow = it)).flow })
            .containsExactly(PageFlow.Paginated, PageFlow.Scrolled).inOrder()
        assertThat(BrightnessEdge.entries.map { page(ReaderSettings(brightnessEdge = it)).brightnessEdge })
            .containsExactly(PageBrightnessEdge.Left, PageBrightnessEdge.Right, PageBrightnessEdge.Off).inOrder()
    }

    @Test
    fun `sizes and spacings follow their steps`() {
        assertThat(FontSizesPx.indices.map { page(ReaderSettings(fontSizeStep = it)).fontSizePx })
            .isEqualTo(FontSizesPx)
        assertThat(LineHeights.indices.map { page(ReaderSettings(lineHeightStep = it)).lineHeight })
            .isEqualTo(LineHeights)
    }

    @Test
    fun `a step out of bounds is the nearest one`() {
        assertThat(page(ReaderSettings(fontSizeStep = 99)).fontSizePx).isEqualTo(FontSizesPx.last())
        assertThat(page(ReaderSettings(lineHeightStep = -3)).lineHeight).isEqualTo(LineHeights.first())
    }

    @Test
    fun `margins grow from narrow to wide and stay within what the page accepts`() {
        val margins = MarginSize.entries.map { page(ReaderSettings(margins = it)).marginPercent }

        assertThat(margins).isInStrictOrder()
        assertThat(margins.first()).isAtLeast(0.0)
        assertThat(margins.last()).isAtMost(40.0)
    }

    @Test
    fun `two pages only where the window has room for them`() {
        assertThat(page(ReaderSettings(), twoPages = true).maxColumns).isEqualTo(2)
        assertThat(page(ReaderSettings(), twoPages = false).maxColumns).isEqualTo(1)
    }

    @Test
    fun `hyphenation follows the setting`() {
        assertThat(page(ReaderSettings(hyphenate = false)).hyphenate).isFalse()
    }

    @Test
    fun `the footnote panel follows the setting`() {
        assertThat(page(ReaderSettings(footnotePanel = false)).footnotePanel).isFalse()
    }

    @Test
    fun `the highlights are blended as the theme says`() {
        val dark = ReaderTheme.Dark.colours(systemDark = false)

        val page = ReaderSettings().toPageSettings(dark, twoPages = false, noteLabels = EnglishNoteLabels)

        assertThat(page.highlightBlend).isEqualTo(PageBlend.Normal)
        assertThat(page.highlightOpacity).isEqualTo(dark.highlightOpacity)
        assertThat(page.searchColor).isEqualTo(dark.searchColour.toCssHex())
    }

    @Test
    fun `the labels of the note panel are those given, in either language`() {
        val french = PageNoteLabels(goTo = "Aller \u00e0 la note", title = "Note")

        val page = ReaderSettings().toPageSettings(light, twoPages = false, noteLabels = french)

        assertThat(page.noteLabels).isEqualTo(french)
    }
}
