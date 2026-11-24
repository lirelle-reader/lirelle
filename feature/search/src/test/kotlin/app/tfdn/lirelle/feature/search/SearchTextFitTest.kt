package app.tfdn.lirelle.feature.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongRecent = "the extraordinarily long text of a search somebody typed one evening"
private const val LongGenre = "Science Fiction and Fantasy for Young Adults"
private const val LongTitle = "The Extraordinarily Long Title of a Book Nobody Could Ever Fit on a Single Line"
private const val LongName = "Bartholomew Featherstonehaugh-Cholmondeley and Associates"
private const val LongSearch = "a search text nobody would ever type in"
private const val BigCount = 1234

// Of a scheme nothing is ever fetched for: the tile of a book without a cover carries its title
// on several lines, which is a drawing of a cover and not a label of the row.
private const val UnfetchedCover = "cover://none"

/** No text of the search screen overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SearchTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val genres = listOf(Genre(LongGenre, BigCount)) +
        (1..15).map { Genre(if (it % 2 == 0) "Genre $it" else "Historical Fiction $it", it) }

    private val results = SearchResults(
        text = "mars",
        books = SearchSection(
            items = (1..10).map {
                sampleBook(
                    id = "$it",
                    title = "$LongTitle $it",
                    author = LongName,
                    media = MediaProfile.ReadAlong,
                    series = SeriesRef(SeriesId("4"), LongName, "12.5"),
                ).copy(coverUrl = UnfetchedCover)
            },
            total = BigCount,
        ),
        series = SearchSection(
            items = (40..45).map {
                sampleSeries("$it", "$LongName $it", books = BigCount).copy(coverUrls = listOf(UnfetchedCover))
            },
            total = BigCount,
        ),
        authors = SearchSection(
            items = (50..55).map { Author(AuthorId("$it"), "$LongName $it", bookCount = BigCount, portraitUrl = null) },
            total = BigCount,
        ),
    )

    private fun fits(state: SearchUiState): List<LaidOutText> {
        val width = if (screen == Wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
        val actions = SearchActions({}, {}, {}, {}, {}, {}, {})
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    SearchContent(state, actions, {}, {}, {}, {}, {})
                }
            }
        }
        return compose.assertAllTextFits()
    }

    private fun List<LaidOutText>.reading(text: String) = filter { it.text == text }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Test
    fun `before typing`() {
        val texts = fits(
            SearchUiState(recent = listOf("mars", LongRecent, "king", "a third one"), genres = genres),
        )

        // The hint and the genres are whole, on as many lines as they need: never cut.
        val whole = texts.reading(string(R.string.search_hint)) + texts.reading(LongGenre) +
            texts.reading(BigCount.toString())
        assertThat(whole).hasSize(3)
        assertThat(whole.filter { it.isEllipsized }).isEmpty()
        assertThat(whole.filter { !it.isWhollyShown }).isEmpty()
        // A recent search is a label of its row: one line.
        assertThat(texts.reading(LongRecent).map { it.lineCount }).containsExactly(1)
        assertThat(texts.filter { it.text.startsWith("Genre ") || it.text.startsWith("Historical ") }).hasSize(15)
    }

    @Test
    fun results() {
        val texts = fits(SearchUiState(text = "mars", results = Loadable.Ready(results)))

        val seeAll = texts.reading(string(R.string.search_see_all_count, BigCount))
        assertThat(seeAll).hasSize(3)
        assertThat(seeAll.filter { it.isEllipsized }).isEmpty()
        assertThat(seeAll.filter { !it.isWhollyShown }).isEmpty()
        val shownBooks = if (screen == Wide) 10 else 5
        assertThat(texts.filter { it.text.startsWith(LongTitle) }).hasSize(shownBooks)
    }

    @Test
    fun `nothing found`() {
        val texts = fits(SearchUiState(text = LongSearch, results = Loadable.Ready(SearchResults(LongSearch))))

        val message = texts.reading(string(R.string.search_nothing, LongSearch)).single()
        assertThat(message.isWhollyShown).isTrue()
    }

    companion object {
        // Tall enough for everything to be laid out without scrolling, at the largest text size.
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
