package app.tfdn.lirelle.core.reader.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderSettingsTest {

    @Test
    fun `the defaults are those of the design`() {
        val settings = ReaderSettings()

        assertThat(settings.theme).isEqualTo(ReaderTheme.System)
        assertThat(settings.font).isEqualTo(ReaderFont.Book)
        assertThat(FontSizesPx[settings.fontSizeStep]).isEqualTo(18)
        assertThat(LineHeights[settings.lineHeightStep]).isEqualTo(1.5)
        assertThat(settings.margins).isEqualTo(MarginSize.Medium)
        assertThat(settings.align).isEqualTo(ReaderAlign.Book)
        assertThat(settings.hyphenate).isTrue()
        assertThat(settings.flow).isEqualTo(ReaderFlow.Paginated)
        assertThat(settings.footnotePanel).isTrue()
        assertThat(settings.cornerBookmark).isTrue()
        assertThat(settings.lastHighlightColour).isEqualTo("#FACC15")
        assertThat(settings.volumeKeys).isFalse()
        assertThat(settings.brightnessEdge).isEqualTo(BrightnessEdge.Left)
        assertThat(settings.brightness).isNull()
        assertThat(settings.header).isEqualTo(RunningLine(RunningSlot.ChapterTitle, RunningSlot.Time))
        assertThat(settings.footer).isEqualTo(RunningLine(RunningSlot.PagesLeftInChapter, RunningSlot.BookPercent))
    }

    @Test
    fun `the step lists grow, so that plus is always larger`() {
        assertThat(FontSizesPx).isInStrictOrder()
        assertThat(LineHeights).isInStrictOrder()
    }

    @Test
    fun `the defaults are already within bounds`() {
        assertThat(ReaderSettings().normalized()).isEqualTo(ReaderSettings())
    }

    @Test
    fun `steps are clamped to their lists`() {
        val low = ReaderSettings(fontSizeStep = -1, lineHeightStep = -4).normalized()
        val high = ReaderSettings(fontSizeStep = 99, lineHeightStep = 99).normalized()

        assertThat(low.fontSizeStep).isEqualTo(0)
        assertThat(low.lineHeightStep).isEqualTo(0)
        assertThat(high.fontSizeStep).isEqualTo(FontSizesPx.lastIndex)
        assertThat(high.lineHeightStep).isEqualTo(LineHeights.lastIndex)
    }

    @Test
    fun `brightness is clamped, and following the system stays as it is`() {
        assertThat(ReaderSettings(brightness = 0f).normalized().brightness).isEqualTo(0.01f)
        assertThat(ReaderSettings(brightness = -3f).normalized().brightness).isEqualTo(0.01f)
        assertThat(ReaderSettings(brightness = 1.7f).normalized().brightness).isEqualTo(1f)
        assertThat(ReaderSettings(brightness = 0.4f).normalized().brightness).isEqualTo(0.4f)
        assertThat(ReaderSettings(brightness = Float.NaN).normalized().brightness).isNull()
        assertThat(ReaderSettings(brightness = null).normalized().brightness).isNull()
    }
}
