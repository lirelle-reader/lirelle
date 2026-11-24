package app.tfdn.lirelle.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBook
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

private const val RowTitle = "Recently added to the whole library"
private const val LongTitle = "The Extraordinarily Long Title of a Book Nobody Could Ever Fit on a Single Line"
private const val UnfetchedCover = "cover://book/"
private const val SeriesName = "A Saga With a Long Name"
private const val Volume = "12.5"
private const val FullPercent = 100
private val CardWidthInARow = 286.dp
private const val LongTimeLeftHours = 12
private const val LongTimeLeftMinutes = 48
private const val LongAuthor = "Bartholomew Featherstonehaugh-Cholmondeley and Associates"

/** The pieces of the home screen: a heading wraps, its button stays whole; cards and covers hold one-line labels. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class HomePiecesFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-13T12:00:00Z")

    // With a cover, of a scheme nothing is ever fetched for: the tile of a book without one carries
    // its title on several lines, which is a drawing of a cover and not a label of the row.
    private val books = (1..3).map {
        sampleBook("$it", "$LongTitle $it", author = LongAuthor, series = SeriesRef(SeriesId("4"), SeriesName, Volume))
            .copy(coverUrl = "$UnfetchedCover$it")
    }

    private val reading = sampleBook(
        id = "7",
        title = LongTitle,
        author = LongAuthor,
        media = MediaProfile.ReadAlong,
        readState = ReadState.Reading,
        progressPercent = FullPercent,
    ).copy(
        coverUrl = "${UnfetchedCover}7",
        lastActivityAt = now.minus(Duration.ofDays(5)),
        // Two digits of hours: about the widest a time left gets.
        timeLeftSeconds = LongTimeLeftHours * 3600L + LongTimeLeftMinutes * 60L,
        hasAudioFiles = true,
    )

    /** An audiobook, whose cover (its own, the medium it prefers) is square. */
    private val audiobook = reading.copy(
        id = BookId("8"),
        media = MediaProfile.AudioOnly,
        coverUrl = "${UnfetchedCover}8",
        coverShape = CoverShape.Square,
    )

    // Every cover carries the mark of a book kept on the device: the most a cover and a card ever hold.
    private val marks = MutableStateFlow<Map<BookId, DownloadMark>>(
        mapOf(
            BookId("1") to DownloadMark.Kept,
            BookId("2") to DownloadMark.InProgress,
            BookId("3") to DownloadMark.Update,
            reading.id to DownloadMark.Update,
            audiobook.id to DownloadMark.Kept,
        ),
    )

    // One piece at a time, each within the screen: `assertAllTextFits` takes a text below the edge
    // of the screen, in a row that scrolls sideways and is itself still partly on screen, for a cut one.
    private fun fits(content: @Composable () -> Unit): List<LaidOutText> {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalDownloadMarks provides marks) { Column { content() } }
            }
        }
        return compose.assertAllTextFits()
    }

    private fun List<LaidOutText>.reading(text: String) = filter { it.text == text }

    private fun assertHeadingIsWhole(texts: List<LaidOutText>) {
        // The heading is whole, on as many lines as it needs; its button is whole too.
        val whole = texts.reading(RowTitle) + texts.reading(compose.activity.getString(R.string.row_see_all))
        assertThat(whole).hasSize(2)
        assertThat(whole.filter { it.isEllipsized }).isEmpty()
        assertThat(whole.filter { !it.isWhollyShown }).isEmpty()
    }

    @Test
    fun `every text of a row of covers fits`() {
        val texts = fits { CoverRow(RowTitle, books, onOpenBook = {}, onSeeAll = {}) }

        assertHeadingIsWhole(texts)
        // Under a cover, a title and an author are labels: one line each, for the three books.
        assertThat(texts.filter { it.text.startsWith(LongTitle) }.map { it.lineCount }).containsExactly(1, 1, 1)
        assertThat(texts.reading(LongAuthor).map { it.lineCount }).containsExactly(1, 1, 1)
    }

    @Test
    fun `every text of a row about series fits`() {
        val texts = fits { CoverRow(RowTitle, books, onOpenBook = {}, onSeeAll = {}, showSeries = true) }

        assertHeadingIsWhole(texts)
        assertThat(texts.reading(SeriesName).map { it.lineCount }).containsExactly(1, 1, 1)
        assertThat(texts.reading(LongAuthor)).isEmpty()
        assertThat(texts.reading(Volume)).hasSize(books.size)
    }

    @Test
    fun `every text of a card and its two buttons fits, as wide as the screen or at the width it has in a row`() {
        val texts = fits {
            ContinueCard(
                reading,
                onOpenBook = {},
                onRead = {},
                onListen = {},
                now = now,
                lastMedium = BookMedium.Listen,
            )
            ContinueCard(
                reading,
                onOpenBook = {},
                onRead = {},
                onListen = {},
                modifier = Modifier.width(CardWidthInARow),
                now = now,
                lastMedium = BookMedium.Read,
            )
        }

        assertThat(texts.reading(LongTitle).map { it.lineCount }).containsExactly(1, 1)
        // The percentage and the time left are never cut short, however little room the card has.
        val values = texts.reading(compose.activity.getString(R.string.progress_percent, FullPercent)) +
            texts.reading(
                compose.activity.getString(R.string.continue_time_left_hours, LongTimeLeftHours, LongTimeLeftMinutes),
            )
        assertThat(values).hasSize(4)
        assertThat(values.filter { it.isEllipsized || it.lineCount != 1 }).isEmpty()
        assertThat(texts.reading(relativeTime(reading.lastActivityAt!!, now))).hasSize(2)
    }

    @Test
    fun `every text of an audiobook's card fits beside a book's, its cover square`() {
        val texts = fits {
            ContinueCard(reading, onOpenBook = {}, onRead = {}, onListen = {}, now = now)
            ContinueCard(audiobook, onOpenBook = {}, onRead = {}, onListen = {}, now = now)
            ContinueCard(audiobook, onOpenBook = {}, onRead = {}, onListen = {}, Modifier.width(CardWidthInARow), now)
        }

        assertThat(texts.reading(LongTitle).map { it.lineCount }).containsExactly(1, 1, 1)
        val time = compose.activity.getString(R.string.continue_time_left_hours, LongTimeLeftHours, LongTimeLeftMinutes)
        assertThat(texts.reading(time).filter { it.isEllipsized }).isEmpty()
        val square = compose.onAllNodesWithTag("continue-cover-8", useUnmergedTree = true).fetchSemanticsNodes()
        assertThat(square).hasSize(2)
        square.forEach { assertThat(it.size.width).isEqualTo(it.size.height) }
        val portrait = compose.onNodeWithTag("continue-cover-7", useUnmergedTree = true).fetchSemanticsNode().size
        assertThat(portrait.height).isGreaterThan(portrait.width)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf("w320dp-h640dp", "w411dp-h891dp").flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
