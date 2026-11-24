package app.tfdn.lirelle.feature.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.SourceCapabilities.Companion.Everything
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.library.books.BookListDetailScreen
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.books.BookListUiState
import app.tfdn.lirelle.feature.library.books.BookListViewModel
import app.tfdn.lirelle.feature.library.books.ChipRowTag
import app.tfdn.lirelle.feature.library.books.SortFilterBar
import app.tfdn.lirelle.feature.library.series.SeriesListUiState
import app.tfdn.lirelle.feature.library.series.SeriesTabContent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the Library overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class LibraryTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val width = if (screen == Tablet) WindowWidthClass.Medium else WindowWidthClass.Compact

    private val bar = BookListUiState(
        request = BookListRequest(
            BookSort.Published,
            SortDirection.Descending,
            BookFilters(formats = setOf(FormatFilter.Audio), readAlongOnly = true),
        ),
        total = 1234,
    )

    private val series = SeriesListUiState(
        series = Loadable.Ready(
            listOf(
                sampleSeries(
                    id = "1",
                    name = "A series whose name is far too long for one line of a tile",
                    books = 120,
                    read = 87,
                ),
                sampleSeries("2"),
            ),
        ),
    )

    @Test
    fun `tabs and the sort and filter bar`() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    LibraryScreen(
                        page = {
                            SortFilterBar(
                                state = bar,
                                onSort = {},
                                onToggleFormat = {},
                                onToggleStatus = {},
                                onReadAlongOnly = {},
                                onPresentation = {},
                                // As in the book list, which keeps this margin around its content.
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        },
                    )
                }
            }
        }

        compose.assertAllTextFits()
        assertEveryChipFitsOnceScrolledTo(chipCount = 4)
    }

    @Test
    fun `series tiles`() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    SeriesTabContent(
                        state = series,
                        onOpenSeries = {},
                        onSort = {},
                        onFilter = {},
                        onRetry = {},
                        onRefresh = {},
                    )
                }
            }
        }

        compose.assertAllTextFits()
        assertEveryChipFitsOnceScrolledTo(chipCount = 2)
    }

    @Test
    fun `a list of books under a long title`() {
        val scope = BookListScope.search(LongSearch)
        val repository = FakeLibraryRepository().apply { books = listOf(sampleBook("1"), sampleBook("2")) }
        val viewModel = BookListViewModel(scope, repository, InMemoryLibraryPreferences(), Everything)
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    BookListDetailScreen(
                        title = stringResource(R.string.books_empty_scope) + " “$LongSearch”",
                        scope = scope,
                        onBack = {},
                        onOpenBook = {},
                        viewModel = viewModel,
                    )
                }
            }
        }

        val title = compose.assertAllTextFits().single { LongSearch in it.text }
        assertWithMessage("$title is whole").that(title.isWhollyShown).isTrue()
    }

    /**
     * The chips are on a line that scrolls sideways. Each is brought into view in turn: its label
     * is then whole, on one line and not cut short, and every other text still fits.
     */
    private fun assertEveryChipFitsOnceScrolledTo(chipCount: Int) {
        val chips = hasClickAction() and hasAnyAncestor(hasTestTag(ChipRowTag))
        compose.onAllNodes(chips).assertCountEquals(chipCount)
        repeat(chipCount) { index ->
            val chip = compose.onAllNodes(chips)[index].performScrollTo().assertIsWhollyShown()
            val labels = chip.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
            assertThat(labels).hasSize(1)

            val label = compose.assertAllTextFits().single { it.text in labels }
            assertWithMessage("lines of the chip $label").that(label.lineCount).isEqualTo(1)
            assertWithMessage("$label is cut short").that(label.isEllipsized).isFalse()
            assertWithMessage("$label is whole").that(label.isWhollyShown).isTrue()
        }
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Tablet = "w700dp-h1000dp"
        private const val LongSearch = "the long road to the lighthouse and other stories of the far coast"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Tablet).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 1.3f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
