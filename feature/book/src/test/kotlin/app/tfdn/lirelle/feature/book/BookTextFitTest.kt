package app.tfdn.lirelle.feature.book

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.R as UiR
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the book page overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class BookTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = sampleBookDetail().copy(
        title = "A title long enough to need several lines on a narrow phone at a large text size",
        publisher = "A publisher with a rather long corporate name, Incorporated",
        genres = listOf("Fantasy", LongGenre),
    )
    private val reading = BookReading(42, Instant.parse("2026-03-01T20:00:00Z"), totalSeconds = 7_200, sessionCount = 5)

    private val related = RelatedBooks(
        inSeries = listOf(
            RelatedBook(
                BookId("13"),
                "A following volume with a long title",
                listOf("Ursula Writer"),
                null,
                CoverShape.Portrait,
            ),
            // Read, with a volume number: the check and the number are both on its cover. (No third
            // book: on a narrow phone it would stick out of the row, which the rule counts as cut.)
            RelatedBook(
                id = BookId("14"),
                title = "Short",
                authors = listOf("Ursula Writer"),
                coverUrl = null,
                coverShape = CoverShape.Square,
                seriesIndex = "12.5",
                readState = ReadState.Read,
            ),
        ),
        sameAuthor = listOf(
            RelatedBook(
                BookId("20"),
                "Read",
                listOf("Ursula Writer"),
                null,
                CoverShape.Square,
                readState = ReadState.Read,
            ),
        ),
    )

    @Test
    fun `the whole page`() {
        val texts = show(BookUiState(Loadable.Ready(book), reading, related))

        val volume = texts.single { it.text == "12.5" }
        assertWithMessage("$volume is cut short").that(volume.isEllipsized).isFalse()
        // A genre is a link that grows with its name: all of it is there, on as many lines as it needs.
        val genre = texts.single { it.text == LongGenre }
        assertWithMessage("$genre is cut short").that(genre.isEllipsized).isFalse()
        // The heading of the row of the series is a line that opens the series: all of it is there.
        compose.onNodeWithTag("book-in-series-open").assertExists()
        val heading = texts.single { it.text == compose.activity.getString(R.string.section_in_series) }
        assertWithMessage("$heading is cut short").that(heading.isEllipsized).isFalse()
    }

    @Test
    fun `the main action says how far the user is without a word being cut`() {
        // The widest it gets: a book at its end goes on until the server says it is read.
        val nearlyDone = reading.copy(progressPercent = 100)

        val texts = show(BookUiState(Loadable.Ready(book.copy(readState = ReadState.Reading)), nearlyDone, related))

        assertActionWhole(texts, R.string.action_read, text(R.string.status_resume_percent, 100))
    }

    @Test
    fun `the main action of a finished book offers to read it again without a word being cut`() {
        val finished = reading.copy(progressPercent = 100)

        val texts = show(BookUiState(Loadable.Ready(book.copy(readState = ReadState.Read)), finished, related))

        assertActionWhole(texts, R.string.action_read, text(R.string.status_read_again))
    }

    @Test
    fun `the listening says how far the user is without a word being cut`() {
        val listening = PlaybackState("aud_1", 60_000, 100.0, false, null, version = "v1", contentVersion = null)
        val state = BookUiState(Loadable.Ready(book), reading, related, listening = Loadable.Ready(listening))

        val texts = show(state)

        assertActionWhole(texts, R.string.action_listen, text(R.string.status_resume_percent, 100))
    }

    @Test
    fun `the listening of a finished audiobook offers to listen again without a word being cut`() {
        val listening = PlaybackState("aud_1", 60_000, 100.0, true, null, version = "v1", contentVersion = null)
        val audiobook = book.copy(media = MediaProfile.AudioOnly)

        val texts = show(BookUiState(Loadable.Ready(audiobook), listening = Loadable.Ready(listening)))

        assertActionWhole(texts, R.string.action_listen, text(R.string.status_listen_again))
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /**
     * Each word of the [name] and of the [status] of a button is a text of its own; the page may say the same word
     * elsewhere ("Read", a read state).
     */
    private fun assertActionWhole(texts: List<LaidOutText>, name: Int, status: String) =
        (text(name).split(' ') + status.split(' ')).forEach { word ->
            val shown = texts.filter { it.text == word && it.isLabel }
            assertWithMessage("\"$word\" of the action").that(shown).isNotEmpty()
            assertWithMessage("\"$word\" is cut short").that(shown.none { it.isEllipsized }).isTrue()
        }

    @Test
    fun `the page of a comic, its chip under a long title and the page it is at`() {
        val comic = sampleComicDetail().copy(title = book.title)

        val texts = show(BookUiState(Loadable.Ready(comic), reading.copy(page = 1_248), related))

        val chip = texts.single { it.text == compose.activity.getString(UiR.string.media_comic) }
        assertWithMessage("$chip is cut short").that(chip.isEllipsized).isFalse()
        val page = texts.single { it.text == compose.activity.getString(R.string.reading_page, 1_248) }
        assertWithMessage("$page is cut short").that(page.isEllipsized).isFalse()
    }

    @Test
    fun `the page of a PDF, its chip under a long title and the page it is at`() =
        assertPagedPageFits("PDF", UiR.string.media_pdf)

    @Test
    fun `the page of a DjVu, its chip under a long title and the page it is at`() =
        assertPagedPageFits("DJVU", UiR.string.media_djvu)

    private fun assertPagedPageFits(format: String, words: Int) {
        val document = samplePagedDetail(format).copy(title = book.title)

        val texts = show(BookUiState(Loadable.Ready(document), reading.copy(page = 1_248), related))

        // The chip, and for a PDF the format of its file where the page lists them: none is cut.
        val named = texts.filter { it.text == compose.activity.getString(words) }
        assertWithMessage("texts that say $format").that(named).isNotEmpty()
        assertWithMessage("$named are cut short").that(named.none { it.isEllipsized }).isTrue()
        val page = texts.single { it.text == compose.activity.getString(R.string.reading_page, 1_248) }
        assertWithMessage("$page is cut short").that(page.isEllipsized).isFalse()
    }

    @Test
    fun `the page over the bar of a refresh that failed`() {
        val state = BookUiState(Loadable.Ready(book), reading, related, refreshError = ApiError.ServerUnreachable)

        show(state)
    }

    private fun show(state: BookUiState): List<LaidOutText> {
        val width = when (screen) {
            Wide -> WindowWidthClass.Expanded
            Tablet -> WindowWidthClass.Medium
            else -> WindowWidthClass.Compact
        }
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    BookContent(
                        state = state,
                        onBack = {},
                        onRetry = {},
                        onRefresh = {},
                        onOpenBook = {},
                        onOpenAuthor = {},
                        onOpenGenre = {},
                        onRead = { _, _ -> },
                        onListen = { _, _ -> },
                        onNarrate = {},
                    )
                }
            }
        }

        return compose.assertAllTextFits()
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Tablet = "w700dp-h1000dp"
        private const val Wide = "w1100dp-h800dp"
        private const val LongGenre = "A genre with a remarkably long name for a chip"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Tablet, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 1.3f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
