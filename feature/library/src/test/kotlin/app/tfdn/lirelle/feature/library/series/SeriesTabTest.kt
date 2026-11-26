package app.tfdn.lirelle.feature.library.series

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.SeriesProgressFilter
import app.tfdn.lirelle.core.library.model.SeriesSort
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LoadingTag
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.core.ui.NoNetworkDownloadsTag
import app.tfdn.lirelle.core.ui.NoNetworkRetryTag
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.feature.library.books.FilterTickTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SeriesTabTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<Any?>()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    /** @param inShell the downloaded books can be reached, as in the shell. */
    private fun show(state: SeriesListUiState, inShell: Boolean = false) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalOpenDownloads provides if (inShell) ({ events += "downloads" }) else null,
                ) {
                    SeriesTabContent(
                        state = state,
                        onOpenSeries = { events += it },
                        onSort = { events += it },
                        onFilter = { events += it },
                        onRetry = { events += "retry" },
                        onRefresh = {},
                    )
                }
            }
        }
    }

    /** The progress bar of a tile. */
    private val isProgressBar = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private val ready = SeriesListUiState(
        series = Loadable.Ready(listOf(sampleSeries("1", name = "Dawn", books = 8, read = 5), sampleSeries("2"))),
    )

    @Test
    fun `a tile shows the name, the authors and how much is read, and opens the series`() {
        show(ready)

        compose.onNodeWithText("Dawn").assertIsDisplayed()
        compose.onNodeWithText("Author 1").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.series_progress, 5, 8)).assertIsDisplayed()
        compose.onAllNodes(isProgressBar).assertCountEquals(2)
        compose.onNodeWithTag("series-1").performClick()

        assertThat(events).containsExactly(SeriesId("1"))
    }

    @Test
    fun `shows the number of series`() {
        show(ready)

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.series_count, 2, 2)).assertIsDisplayed()
    }

    @Test
    fun `the sort menu offers the five sorts`() {
        show(ready)

        compose.onNodeWithContentDescription(text(R.string.sort_ascending, text(R.string.sort_name))).performClick()
        listOf(R.string.sort_book_count, R.string.sort_recent, R.string.sort_progress).forEach {
            compose.onNodeWithText(text(it)).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.sort_unread_count)).performClick()

        assertThat(events).containsExactly(SeriesSort.UnreadCount)
    }

    @Test
    fun `the progress filter reports the choice`() {
        show(ready)

        compose.onNodeWithText(text(R.string.filter_progress)).performClick()
        compose.onNodeWithText(text(R.string.progress_in_progress)).performClick()

        assertThat(events).containsExactly(SeriesProgressFilter.InProgress)
    }

    @Test
    fun `without series counts a tile has no progress line`() {
        show(ready.copy(showCounts = false))

        compose.onNodeWithText("Dawn").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.series_progress, 5, 8)).assertDoesNotExist()
        compose.onAllNodes(isProgressBar).assertCountEquals(0)
    }

    @Test
    fun `without series counts the sort menu offers the name and the recently added`() {
        show(ready.copy(showCounts = false))

        compose.onNodeWithContentDescription(text(R.string.sort_ascending, text(R.string.sort_name))).performClick()
        compose.onNodeWithText(text(R.string.sort_recent)).assertIsDisplayed()
        listOf(R.string.sort_book_count, R.string.sort_unread_count, R.string.sort_progress).forEach {
            compose.onNodeWithText(text(it)).assertDoesNotExist()
        }
    }

    @Test
    fun `without series counts there is no progress filter`() {
        show(ready.copy(showCounts = false))

        compose.onNodeWithText(text(R.string.filter_progress)).assertDoesNotExist()
    }

    @Test
    fun `loading shows placeholders`() {
        show(SeriesListUiState())

        compose.onNodeWithTag(LoadingTag).assertIsDisplayed()
    }

    @Test
    fun `a failure shows the error and retries`() {
        show(SeriesListUiState(series = Loadable.Failed(ApiError.ServerUnreachable)))

        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.state_retry)).performClick()

        assertThat(events).containsExactly("retry")
    }

    @Test
    fun `a failure for lack of network leads to the downloads or tries again`() {
        show(SeriesListUiState(series = Loadable.Failed(ApiError.ServerUnreachable)), inShell = true)

        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkDownloadsTag).performScrollTo().performClick()
        compose.onNodeWithTag(NoNetworkRetryTag).performScrollTo().performClick()

        assertThat(events).containsExactly("downloads", "retry").inOrder()
    }

    @Test
    fun `another failure is the error, in the shell too, and a lack of network outside it`() {
        show(SeriesListUiState(series = Loadable.Failed(ApiError.ServerFailed)), inShell = true)

        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.library_error_server_failed))
            .assertIsDisplayed()
    }

    @Test
    fun `outside the shell a lack of network is the error`() {
        show(SeriesListUiState(series = Loadable.Failed(ApiError.ServerUnreachable)))

        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.library_error_unreachable))
            .assertIsDisplayed()
    }

    @Test
    fun `an empty library and an empty filter result say different things`() {
        show(SeriesListUiState(series = Loadable.Ready(emptyList()), filter = SeriesProgressFilter.Complete))

        compose.onNodeWithText(text(R.string.series_empty_filtered)).assertIsDisplayed()
    }

    @Test
    fun `a filter that matches nothing can be cleared from its message`() {
        show(SeriesListUiState(series = Loadable.Ready(emptyList()), filter = SeriesProgressFilter.Complete))

        compose.onNodeWithText(text(R.string.series_clear_filter)).performClick()

        assertThat(events).containsExactly(null)
    }

    @Test
    fun `an empty library has no filter to clear`() {
        show(SeriesListUiState(series = Loadable.Ready(emptyList())))

        compose.onNodeWithText(text(R.string.series_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.series_clear_filter)).assertDoesNotExist()
    }

    @Test
    fun `an active progress filter is marked with a tick, and the sort is not`() {
        show(ready.copy(filter = SeriesProgressFilter.InProgress))

        compose.onAllNodesWithTag(FilterTickTag, useUnmergedTree = true).assertCountEquals(1)
        // A chip is one node for accessibility; its tick is found inside it.
        val progressChip = hasClickAction() and hasAnyDescendant(hasText(text(R.string.filter_progress)))
        compose.onNode(hasTestTag(FilterTickTag) and hasAnyAncestor(progressChip), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `showing every series ticks nothing`() {
        show(ready)

        compose.onAllNodesWithTag(FilterTickTag, useUnmergedTree = true).assertCountEquals(0)
    }
}
