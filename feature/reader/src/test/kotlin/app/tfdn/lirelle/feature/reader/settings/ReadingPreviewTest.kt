package app.tfdn.lirelle.feature.reader.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.feature.reader.FooterPreviewTag
import app.tfdn.lirelle.feature.reader.HeaderPreviewTag
import app.tfdn.lirelle.feature.reader.R
import app.tfdn.lirelle.feature.reader.colours
import app.tfdn.lirelle.feature.reader.label
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The preview of the Reading page follows the settings a book is drawn with. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingPreviewTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val host = PageHost()

    /** @param night whether the app is in its dark theme, which the reading theme "Auto" follows. */
    private fun show(settings: ReaderSettings = ReaderSettings(), night: Boolean = false) {
        host.settings = settings
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                LirelleTheme { ReadingPreview(host.stored) }
            }
        }
    }

    private fun inkOf(theme: ReaderTheme, night: Boolean = false) = theme.colours(systemDark = night).foreground

    @Test
    fun `it shows a header line, the sample and a footer line`() {
        show()

        compose.onNodeWithTag(HeaderPreviewTag).assertExists()
        compose.onNodeWithText(string(R.string.reader_preview_text)).assertExists()
        compose.onNodeWithTag(FooterPreviewTag).assertExists()
    }

    @Test
    fun `it is drawn in the reading theme's colours and names the theme`() {
        show()
        (ReaderTheme.entries - ReaderTheme.System).forEach { theme ->
            host.settings = ReaderSettings(theme = theme)
            compose.waitForIdle()

            assertWithMessage("ink of $theme").that(compose.sampleLayout().layoutInput.style.color)
                .isEqualTo(inkOf(theme))
            assertWithMessage("state of $theme").that(compose.previewState()).contains(string(theme.label))
        }
    }

    @Test
    fun `the theme Auto is light when the app is light`() {
        show(ReaderSettings(theme = ReaderTheme.System), night = false)

        assertThat(compose.sampleLayout().layoutInput.style.color).isEqualTo(inkOf(ReaderTheme.Light))
    }

    @Test
    fun `the theme Auto is dark when the app is dark`() {
        show(ReaderSettings(theme = ReaderTheme.System), night = true)

        assertThat(compose.sampleLayout().layoutInput.style.color).isEqualTo(inkOf(ReaderTheme.Dark))
        assertThat(compose.previewState()).contains(string(R.string.reader_theme_system))
    }

    @Test
    fun `it follows the text size and the line spacing, and tells the size`() {
        show(ReaderSettings(fontSizeStep = 0, lineHeightStep = 0))
        var style = compose.sampleLayout().layoutInput.style
        assertThat(style.fontSize).isEqualTo(FontSizesPx.first().sp)
        assertThat(style.lineHeight).isEqualTo(LineHeights.first().em)
        assertThat(compose.previewState()).contains(FontSizesPx.first().toString())

        host.settings = ReaderSettings(fontSizeStep = FontSizesPx.lastIndex, lineHeightStep = LineHeights.lastIndex)
        compose.waitForIdle()

        style = compose.sampleLayout().layoutInput.style
        assertThat(style.fontSize).isEqualTo(FontSizesPx.last().sp)
        assertThat(style.lineHeight).isEqualTo(LineHeights.last().em)
        assertThat(compose.previewState()).contains(FontSizesPx.last().toString())
    }

    @Test
    fun `it shows six lines at most, the rest cut`() {
        show(ReaderSettings(fontSizeStep = FontSizesPx.lastIndex))

        val layout = compose.sampleLayout()

        assertThat(layout.lineCount).isEqualTo(6)
        assertThat(layout.isLineEllipsized(5)).isTrue()
    }

    @Test
    fun `it is justified when the text is, and starts at the start otherwise`() {
        val expected = mapOf(
            ReaderAlign.Justify to TextAlign.Justify,
            ReaderAlign.Start to TextAlign.Start,
            ReaderAlign.Book to TextAlign.Start,
        )
        show()
        expected.forEach { (align, textAlign) ->
            host.settings = ReaderSettings(align = align)
            compose.waitForIdle()

            assertWithMessage("$align").that(compose.sampleLayout().layoutInput.style.textAlign).isEqualTo(textAlign)
        }
    }

    @Test
    fun `it breaks words between lines only when hyphenation is on`() {
        show(ReaderSettings(hyphenate = true))
        assertThat(compose.sampleLayout().layoutInput.style.hyphens).isEqualTo(Hyphens.Auto)

        host.settings = ReaderSettings(hyphenate = false)
        compose.waitForIdle()

        assertThat(compose.sampleLayout().layoutInput.style.hyphens).isEqualTo(Hyphens.None)
    }

    @Test
    fun `the margins are the room at the sides of the sample`() {
        val expected = mapOf(MarginSize.Narrow to 8.dp, MarginSize.Medium to 16.dp, MarginSize.Wide to 28.dp)
        show()
        expected.forEach { (margins, room) ->
            host.settings = ReaderSettings(margins = margins)
            compose.waitForIdle()

            val card = compose.preview().bounds()
            val sample = compose.onNodeWithText(string(R.string.reader_preview_text), useUnmergedTree = true).bounds()
            val pixels = with(compose.density) { room.toPx() }
            assertWithMessage("$margins, at the left").that(sample.left - card.left).isWithin(1f).of(pixels)
            assertWithMessage("$margins, at the right").that(card.right - sample.right).isWithin(1f).of(pixels)
        }
    }

    @Test
    fun `it is drawn in the font chosen, a generic serif for the book's own layout`() {
        val families = PreviewFonts.families(ApplicationProvider.getApplicationContext<Context>())
        show()
        ReaderFont.entries.forEach { font ->
            host.settings = ReaderSettings(font = font)
            compose.waitForIdle()

            val family = compose.sampleLayout().layoutInput.style.fontFamily
            assertWithMessage("$font").that(family).isEqualTo(families.getValue(font))
        }
        assertThat(families.getValue(ReaderFont.Book)).isEqualTo(FontFamily.Serif)
    }

    @Test
    fun `its header and footer lines show what the four ends are set to`() {
        show(
            ReaderSettings(
                header = RunningLine(RunningSlot.BookTitle, RunningSlot.Nothing),
                footer = RunningLine(RunningSlot.Nothing, RunningSlot.PagesLeftInChapter),
            ),
        )
        val title = string(R.string.reader_sample_book_title)
        val pagesLeft = string(R.string.reader_sample_pages_left)
        compose.onNodeWithTag(HeaderPreviewTag)
            .assert(hasContentDescription(string(R.string.reader_running_preview_header, title)))
        compose.onNodeWithTag(FooterPreviewTag)
            .assert(hasContentDescription(string(R.string.reader_running_preview_footer, pagesLeft)))

        host.settings = host.stored.copy(header = RunningLine(RunningSlot.Nothing, RunningSlot.ChapterTitle))
        compose.waitForIdle()

        val chapter = string(R.string.reader_sample_chapter_title)
        compose.onNodeWithTag(HeaderPreviewTag)
            .assert(hasContentDescription(string(R.string.reader_running_preview_header, chapter)))
    }
}
