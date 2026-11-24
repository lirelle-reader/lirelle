package app.tfdn.lirelle.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.reader.web.testBook
import app.tfdn.lirelle.core.ui.relativeTime
import com.google.common.truth.Truth.assertWithMessage
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode
import app.tfdn.lirelle.core.ui.R as UiR

/** No text of the reader overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ReaderTextFitTest(private val screen: String, language: String, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)

    /** A book on screen with a long title and a long chapter name, without header nor footer. */
    private val reading by lazy {
        ReaderUiState(
            title = LongTitle,
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = DeepToc,
            location = Location(LongChapter, tocId = 4, fraction = 0.999, page = 2, pages = 123, atEnd = false),
            settings = ReaderSettings(header = nothing, footer = nothing),
        )
    }

    /** The texts in a slot of one line: they end with an ellipsis instead of wrapping. */
    private val oneLine = setOf(LongTitle, LongChapter)

    @Test
    fun `each failure`() {
        listOf(
            ReaderFailure.Download(ApiError.ServerUnreachable),
            ReaderFailure.Download(ApiError.NotFound),
            ReaderFailure.NotAnEpub,
            ReaderFailure.Protected,
            ReaderFailure.Engine,
            ReaderFailure.CannotOpen,
            ReaderFailure.NoEpub,
            ReaderFailure.OldWebView,
            ReaderFailure.DownloadNotAllowed,
        ).forEach { reason ->
            show(ReaderUiState(title = LongTitle, phase = ReaderPhase.Failed(reason)))
        }
    }

    @Test
    fun `a download`() {
        val progress = DownloadProgress(bytesRead = 123_456_789, totalBytes = 987_654_321)
        show(ReaderUiState(title = LongTitle, phase = ReaderPhase.Downloading(progress)))
        show(ReaderUiState(title = LongTitle, phase = ReaderPhase.Downloading(null)))
    }

    @Test
    fun `the controls, with a long title and a long chapter name`() {
        val texts = show(reading.copy(controlsShown = true), unbreakable = oneLine)

        oneLine.forEach { long ->
            val text = texts.single { it.text == long }
            assertWithMessage("lines of $text").that(text.lineCount).isEqualTo(1)
        }
    }

    @Test
    fun `the banner of a newer position, alone and under the top bar`() {
        val offered = OfferedPosition(percent = 100.0, updatedAt = null)
        show(reading.copy(newerPosition = offered))
        show(reading.copy(newerPosition = offered, controlsShown = true), unbreakable = oneLine)
    }

    @Test
    fun `the banner of a newer position with its age wraps, and its two buttons stay whole`() {
        val offered = OfferedPosition(percent = 100.0, updatedAt = Now.minus(Duration.ofMinutes(59)))
        val buttons = listOf(R.string.reader_newer_position_ignore, R.string.reader_newer_position_go)
            .map { compose.activity.getString(it) }
        // In the middle of the sentence, as the banner writes it: « il y a 59 minutes », not « Il y a ».
        val locale = compose.activity.resources.configuration.locales[0]
        val age = relativeTime(offered.updatedAt!!, Now).replaceFirstChar { it.lowercase(locale) }

        listOf(false, true).forEach { controls ->
            val texts = show(reading.copy(newerPosition = offered, controlsShown = controls), unbreakable = oneLine)

            val sentence = texts.single { it.text.contains(age) }
            if (screen == Narrow && fontScale == 2.0f) {
                assertWithMessage("lines of $sentence").that(sentence.lineCount).isGreaterThan(1)
            }
            buttons.forEach { label ->
                val button = texts.single { it.text == label }
                assertWithMessage("lines of $button").that(button.lineCount).isEqualTo(1)
                assertWithMessage("$button is cut short").that(button.isEllipsized).isFalse()
            }
        }
    }

    @Test
    fun `the go-back button, in the footer and above the bottom bar`() {
        show(reading.copy(goBackShown = true))
        show(reading.copy(goBackShown = true, controlsShown = true), unbreakable = oneLine)
    }

    @Test
    fun `go back in the footer stays whole, the slots beside it end with an ellipsis`() {
        val goBack = compose.activity.getString(R.string.reader_go_back)
        // What the slots may show, which may end with an ellipsis beside go back.
        val percents = listOf(99, 100).map { compose.activity.getString(UiR.string.progress_percent, it) }
        val slots = oneLine + pagesLeft() + percents + Time
        listOf(
            RunningLine(RunningSlot.BookTitle, RunningSlot.ChapterTitle),
            RunningLine(RunningSlot.PagesLeftInChapter, RunningSlot.BookPercent),
            RunningLine(RunningSlot.Time, RunningSlot.Battery),
            RunningLine(RunningSlot.ChapterTitle, RunningSlot.Nothing),
        ).forEach { footer ->
            val settings = ReaderSettings(header = nothing, footer = footer)
            val texts = show(reading.copy(settings = settings, goBackShown = true), unbreakable = slots)

            val button = texts.single { it.text == goBack }
            assertWithMessage("lines of $button").that(button.lineCount).isEqualTo(1)
            assertWithMessage("$button is cut short").that(button.isEllipsized).isFalse()
            texts.filter { it !== button }.forEach { slot ->
                assertWithMessage("lines of $slot").that(slot.lineCount).isEqualTo(1)
                assertWithMessage("$slot overlaps $button").that(slot.boundsInRoot.overlaps(button.boundsInRoot))
                    .isFalse()
            }
            if (screen != Wide && footer.left == RunningSlot.BookTitle) {
                texts.filter { it.text in oneLine }.forEach {
                    assertWithMessage("$it is cut short").that(it.isEllipsized).isTrue()
                }
            }
        }
    }

    @Test
    fun `go back rises into the page's empty bottom band only, and the footer keeps the header's height`() {
        val settings = ReaderSettings(
            header = RunningLine(RunningSlot.BookTitle, RunningSlot.Nothing),
            footer = RunningLine(RunningSlot.ChapterTitle, RunningSlot.BookPercent),
        )
        show(reading.copy(settings = settings), unbreakable = oneLine)
        val page = bounds(PageTag)
        val footer = bounds(RunningFooterTag)
        assertWithMessage("footer").that(footer.height.value).isWithin(0.5f).of(bounds(RunningHeaderTag).height.value)

        show(reading.copy(settings = settings, goBackShown = true), unbreakable = oneLine)
        val goBack = bounds(GoBackTag)
        assertWithMessage("page").that(bounds(PageTag)).isEqualTo(page)
        assertWithMessage("footer").that(bounds(RunningFooterTag)).isEqualTo(footer)
        assertWithMessage("rise").that((footer.top - goBack.top).value).isAtMost(PageBottomBand.value + 0.5f)
        assertWithMessage("top").that(goBack.top.value).isAtLeast((page.bottom - PageBottomBand).value - 0.5f)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    fun `go back in a footer that shows nothing else`() {
        val texts = show(reading.copy(goBackShown = true))

        val button = texts.single { it.text == compose.activity.getString(R.string.reader_go_back) }
        assertWithMessage("$button is cut short").that(button.isEllipsized).isFalse()
    }

    @Test
    fun `the question before a link to the web, with a long address`() {
        show(reading.copy(externalLink = LongUrl), unbreakable = setOf(LongUrl))
    }

    @Test
    fun `the contents, deeply nested and with long labels`() {
        // The label of an entry is meant to be cut: two lines, then an ellipsis.
        val labels = DeepToc.map { it.label }.toSet()
        val texts = show(reading.copy(openSheet = ReaderSheet.Contents), unbreakable = labels)

        assertWithMessage("entries shown").that(texts.count { it.text in labels }).isAtLeast(2)
    }

    @Test
    fun `the header and the footer, with every kind of slot`() {
        val lines = listOf(
            RunningLine(RunningSlot.BookTitle, RunningSlot.Time) to
                RunningLine(RunningSlot.ChapterTitle, RunningSlot.Battery),
            RunningLine(RunningSlot.BookTitle, RunningSlot.ChapterTitle) to
                RunningLine(RunningSlot.PagesLeftInChapter, RunningSlot.BookPercent),
            RunningLine(RunningSlot.Time, RunningSlot.PagesLeftInChapter) to
                RunningLine(RunningSlot.Battery, RunningSlot.Nothing),
            RunningLine(RunningSlot.ChapterTitle, RunningSlot.ChapterPercent) to
                RunningLine(RunningSlot.ChapterPercent, RunningSlot.BookPercent),
            RunningLine(RunningSlot.ChapterPercent, RunningSlot.PagesLeftInChapter) to
                RunningLine(RunningSlot.Time, RunningSlot.ChapterPercent),
        )
        lines.forEach { (header, footer) ->
            val texts = show(
                state = reading.copy(settings = ReaderSettings(header = header, footer = footer)),
                unbreakable = oneLine + pagesLeft(),
            )
            texts.forEach { assertWithMessage("lines of $it").that(it.lineCount).isEqualTo(1) }
        }
    }

    @Test
    fun `the brightness while a finger changes it`() {
        val settings = reading.settings.copy(brightness = 1f)
        val texts = show(reading.copy(adjustingBrightness = true, settings = settings))

        assertWithMessage("texts of the indicator").that(texts).hasSize(1)
    }

    /** At the largest text on the narrowest screen this slot too may end with an ellipsis. */
    private fun pagesLeft() = compose.activity.resources.getQuantityString(R.plurals.reader_pages_left, 121, 121)

    private var shown by mutableStateOf(ReaderUiState())
    private var composed = false

    private fun show(state: ReaderUiState, unbreakable: Set<String> = emptySet()): List<LaidOutText> {
        shown = state
        if (!composed) {
            composed = true
            val width = if (screen == Wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
            compose.setContent {
                LirelleTheme {
                    CompositionLocalProvider(LocalWindowWidthClass provides width) {
                        ReaderContent(
                            state = shown,
                            actions = RecordingActions(),
                            engineSlot = { Box(it.testTag(PageTag)) },
                            device = DeviceReadings(time = Time, batteryPercent = 100, now = Now),
                        )
                    }
                }
            }
        }
        return compose.assertAllTextFits(unbreakable)
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private const val Time = "12:34 PM"
        private const val PageTag = "page"
        private val Now: Instant = Instant.parse("2026-11-14T10:00:00Z")

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
