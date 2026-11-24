package app.tfdn.lirelle.feature.library

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SeriesProgressFilter
import app.tfdn.lirelle.core.library.model.SeriesSort
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.core.testing.laidOutTexts
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.library.books.BookListUiState
import app.tfdn.lirelle.feature.library.books.ChipRowTag
import app.tfdn.lirelle.feature.library.books.SortFilterBar
import app.tfdn.lirelle.feature.library.series.SeriesListUiState
import app.tfdn.lirelle.feature.library.series.SeriesTabContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The chips above the books and the series are on one line that scrolls sideways. On the
 * narrowest phone, in French, at the largest text size, each can be brought into view and used.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ChipRowScrollTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "fr-w320dp-h640dp", fontScale = 2.0f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<Any?>()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val chips = hasClickAction() and hasAnyAncestor(hasTestTag(ChipRowTag))

    /** Every filter on: the ticks make the chips as wide as they get. */
    private val everyFilter = BookFilters(
        formats = setOf(FormatFilter.Audio),
        statuses = setOf(StatusFilter.Reading),
        readAlongOnly = true,
    )

    private fun showBooksBar() {
        compose.setContent {
            LirelleTheme {
                // As in the book list: the bar is an item of a list that keeps a margin around its content.
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        SortFilterBar(
                            state = BookListUiState(request = BookListRequest(filters = everyFilter), total = 721),
                            onSort = { events += it },
                            onToggleFormat = { events += it },
                            onToggleStatus = { events += it },
                            onReadAlongOnly = { events += "readAlong=$it" },
                            onPresentation = { events += it },
                        )
                    }
                }
            }
        }
    }

    private fun showSeries() {
        val series = Loadable.Ready(listOf(sampleSeries("1"), sampleSeries("2")))
        compose.setContent {
            LirelleTheme {
                SeriesTabContent(
                    state = SeriesListUiState(series = series, filter = SeriesProgressFilter.InProgress),
                    onOpenSeries = { events += it },
                    onSort = { events += it },
                    onFilter = { events += it },
                    onRetry = {},
                    onRefresh = {},
                )
            }
        }
    }

    private fun sortChip(sort: Int) =
        compose.onNodeWithContentDescription(text(R.string.sort_descending, text(sort)))

    /** Brings a chip into view; its label must then be whole, on one line and not cut short. */
    private fun SemanticsNodeInteraction.scrolledIntoView(label: Int): SemanticsNodeInteraction {
        performScrollTo()
        assertIsDisplayed()
        assertIsWhollyShown()
        val laidOut = compose.laidOutTexts().single { it.text == text(label) }
        assertThat(laidOut.lineCount).isEqualTo(1)
        assertThat(laidOut.isEllipsized).isFalse()
        assertThat(laidOut.isWhollyShown).isTrue()
        return this
    }

    private fun assertOneLineFromEdgeToEdge(chipCount: Int) {
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(ChipRowTag).getUnclippedBoundsInRoot()
        assertThat(row.left).isEqualTo(screen.left)
        assertThat(row.right).isEqualTo(screen.right)

        val all = compose.onAllNodes(chips)
        all.assertCountEquals(chipCount)
        val tops = (0 until chipCount).map { all[it].getUnclippedBoundsInRoot().top }
        assertThat(tops.toSet()).hasSize(1)
        // They do not all fit: the row is wider than the screen, and the rest is reached by scrolling.
        assertThat(all[chipCount - 1].getUnclippedBoundsInRoot().right).isGreaterThan(screen.right)
        // The first chip starts where the content of the list does.
        assertThat(all[0].getUnclippedBoundsInRoot().left).isEqualTo(16.dp)
    }

    @Test
    fun `the chips above the books are on one line from edge to edge`() {
        showBooksBar()

        assertOneLineFromEdgeToEdge(chipCount = 4)
    }

    @Test
    fun `the sort chip can be scrolled into view and used`() {
        showBooksBar()
        compose.onNodeWithText(text(R.string.filter_read_along)).performScrollTo()

        sortChip(R.string.sort_added).scrolledIntoView(R.string.sort_added).performClick()
        compose.onNodeWithText(text(R.string.sort_series)).performClick()

        assertThat(events).containsExactly(BookSort.Series)
    }

    @Test
    fun `the format chip can be scrolled into view and used`() {
        showBooksBar()

        compose.onNodeWithText(text(R.string.filter_format)).scrolledIntoView(R.string.filter_format).performClick()
        compose.onNodeWithText(text(R.string.format_comics)).performClick()

        assertThat(events).containsExactly(FormatFilter.Comics)
    }

    @Test
    fun `the status chip can be scrolled into view and used`() {
        showBooksBar()

        compose.onNodeWithText(text(R.string.filter_status)).scrolledIntoView(R.string.filter_status).performClick()
        compose.onNodeWithText(text(R.string.status_abandoned)).performClick()

        assertThat(events).containsExactly(StatusFilter.Abandoned)
    }

    @Test
    fun `the read-along chip can be scrolled into view and used`() {
        showBooksBar()

        compose.onNodeWithText(text(R.string.filter_read_along))
            .scrolledIntoView(R.string.filter_read_along)
            .performClick()

        assertThat(events).containsExactly("readAlong=false")
    }

    @Test
    fun `the chips above the series are on one line from edge to edge`() {
        showSeries()

        assertOneLineFromEdgeToEdge(chipCount = 2)
    }

    @Test
    fun `the chips above the series can be scrolled into view and used`() {
        showSeries()

        compose.onNodeWithText(text(R.string.filter_progress)).scrolledIntoView(R.string.filter_progress).performClick()
        compose.onNodeWithText(text(R.string.progress_complete)).performClick()
        compose.onNodeWithContentDescription(text(R.string.sort_ascending, text(R.string.sort_name)))
            .scrolledIntoView(R.string.sort_name)
            .performClick()
        compose.onNodeWithText(text(R.string.sort_recent)).performClick()

        assertThat(events).containsExactly(SeriesProgressFilter.Complete, SeriesSort.RecentlyAdded).inOrder()
    }
}
