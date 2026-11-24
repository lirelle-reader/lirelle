package app.tfdn.lirelle.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.BookMedium
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.mediumButtonTag
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongTitle = "The Extraordinarily Long Title of a Book Nobody Could Ever Fit on a Single Line"
private const val LongAuthor = "Bartholomew Featherstonehaugh-Cholmondeley and Associates"
private const val SeriesName = "A Saga With a Rather Long Name"
private const val Volume = "12.5"

// A cover nothing is ever fetched for: the tile of a book without one carries its title on
// several lines, which is a drawing of a cover and not a label of the row.
private const val UnfetchedCover = "cover://none"

/** No text of Home overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class HomeTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-13T12:00:00Z")

    private fun books(kind: HomeRowKind) = listOf(
        sampleBook(
            id = "${kind.name}-1",
            title = LongTitle,
            author = LongAuthor,
            media = MediaProfile.ReadAlong,
            series = SeriesRef(SeriesId("4"), SeriesName, Volume),
            readState = ReadState.Reading,
            progressPercent = 100,
        ).copy(coverUrl = UnfetchedCover, lastActivityAt = now.minus(Duration.ofDays(5)), hasAudioFiles = true),
        sampleBook(
            id = "${kind.name}-2",
            title = LongTitle,
            author = LongAuthor,
            series = SeriesRef(SeriesId("4"), SeriesName, "3"),
            progressPercent = 7,
        ).copy(coverUrl = UnfetchedCover, lastActivityAt = now.minus(Duration.ofHours(2))),
    )

    private val full = HomeUiState(
        HomeRowKind.entries.associateWith { Loadable.Ready(HomeRow(it, books(it), hasMore = true)) },
        // The read-along book of the "Continue" row has both buttons, with two digits of hours left.
        listening = mapOf(
            BookId("Continue-1") to BookListening(timeLeftSeconds = 12 * 3600L + 48 * 60, listenedAt = now),
        ),
    )

    private fun fits(state: HomeUiState): List<LaidOutText> {
        val width = if (screen == Wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    HomeContent(
                        state = state.copy(now = now),
                        onRetry = {},
                        onRefresh = {},
                        onOpenBook = {},
                        onRead = {},
                        onListen = {},
                        onNarrate = {},
                        onSeeAll = {},
                        onSignInAgain = {},
                        onOpenDownloads = {},
                    )
                }
            }
        }
        return compose.assertAllTextFits()
    }

    private fun assertWhole(texts: List<LaidOutText>, text: String, count: Int = 1) {
        val found = texts.filter { it.text == text }
        assertWithMessage("\"$text\" on screen").that(found).hasSize(count)
        assertWithMessage("\"$text\" cut short").that(found.filter { it.isEllipsized || !it.isWhollyShown }).isEmpty()
    }

    @Test
    fun `the four rows, each with more to see`() {
        val texts = fits(full)

        // The title and the row titles are whole, on as many lines as they need.
        assertWhole(texts, compose.activity.getString(R.string.home_title))
        HomeRowKind.entries.forEach { assertWhole(texts, compose.activity.getString(it.titleRes())) }
        // The volume number is on the covers of the row about series, and never cut short.
        assertWhole(texts, Volume)
        assertThat(texts.filter { it.text == SeriesName }).hasSize(2)
    }

    @Test
    fun `the buttons of a book of the continue row show their values whole`() {
        val texts = fits(full)

        val percent = app.tfdn.lirelle.core.ui.R.string.progress_percent
        val left = app.tfdn.lirelle.core.ui.R.string.continue_time_left_hours
        // The first card, a read-along book, has both values. (The second is partly out of the row on a phone.)
        assertWhole(texts, compose.activity.getString(percent, 100))
        assertWhole(texts, compose.activity.getString(left, 12, 48))
    }

    @Test
    fun `a square audiobook card beside a portrait one is as tall, its button on the same bottom line`() {
        val portrait = books(HomeRowKind.Continue).first()
        val square = sampleBook(id = "90", title = LongTitle, author = LongAuthor, media = MediaProfile.AudioOnly)
            .copy(
                coverUrl = UnfetchedCover,
                coverShape = CoverShape.Square,
                lastActivityAt = now.minus(Duration.ofHours(1)),
            )
        val row = HomeRowKind.Continue to Loadable.Ready(HomeRow(HomeRowKind.Continue, listOf(portrait, square), false))

        fits(full.copy(rows = full.rows + row))

        val portraitCard = bounds("continue-${portrait.id}")
        val squareCard = bounds("continue-90")
        assertThat(squareCard.height).isEqualTo(portraitCard.height)
        // The lowest button of each card: stacked or side by side, they end on one line.
        val portraitButton = bounds(mediumButtonTag(BookMedium.Listen, portrait.id))
        val squareButton = bounds(mediumButtonTag(BookMedium.Listen, square.id))
        assertThat(squareButton.bottom).isEqualTo(portraitButton.bottom)
        // The square cover sits in the middle of the room a portrait one takes.
        val squareCover = bounds("continue-cover-90")
        val portraitCover = bounds("continue-cover-${portrait.id}")
        val squareMiddle = (squareCover.top + squareCover.bottom).value / 2
        val portraitMiddle = (portraitCover.top + portraitCover.bottom).value / 2
        assertThat(squareMiddle).isWithin(1f).of(portraitMiddle)
        assertThat(squareCover.width).isEqualTo(squareCover.height)
    }

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    fun `a failed row and its bar`() {
        val failed = HomeRowKind.UpNext to Loadable.Failed(ApiError.ServerUnreachable)
        val texts = fits(full.copy(rows = full.rows + failed))

        assertWhole(texts, compose.activity.getString(app.tfdn.lirelle.core.ui.R.string.state_retry))
        assertThat(texts.filter { it.text == compose.activity.getString(R.string.home_row_up_next) }).isEmpty()
    }

    @Test
    fun `an empty library`() {
        val empty = HomeUiState(HomeRowKind.entries.associateWith { Loadable.Ready(HomeRow(it, emptyList(), false)) })

        assertWhole(fits(empty), compose.activity.getString(R.string.home_empty))
    }

    companion object {
        // Tall enough for the whole of Home at twice the text size: the rule takes a text below the
        // bottom edge, in a row that scrolls sideways and is still partly on screen, for a cut one.
        private const val Narrow = "w320dp-h3200dp"
        private const val Phone = "w411dp-h3200dp"
        private const val Wide = "w1280dp-h3200dp"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
