package app.tfdn.lirelle.feature.library.books

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.feature.library.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SortFilterBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<Any>()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `a list about one status does not offer the status filter, and keeps the others`() {
        val locked = BookFilters(statuses = setOf(StatusFilter.WantToRead))
        show(BookListUiState(request = BookListRequest(filters = locked), total = 3, statusLocked = true))

        compose.onNodeWithText(text(R.string.filter_status)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.filter_format)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.filter_read_along)).assertIsDisplayed()
    }

    @Test
    fun `a list that is not about a status offers the status filter`() {
        show()

        compose.onNodeWithText(text(R.string.filter_status)).assertIsDisplayed()
    }

    @Test
    fun `a Komga-like source offers no Author sort, three statuses, no Audio and no read-along chip`() {
        show(BookListUiState(total = 3, choices = BookListChoices.of(KomgaLikeCapabilities)))

        compose.onNodeWithText(text(R.string.filter_read_along)).assertDoesNotExist()

        compose.onNodeWithContentDescription(text(R.string.sort_descending, text(R.string.sort_added))).performClick()
        listOf(R.string.sort_title, R.string.sort_series, R.string.sort_published).forEach {
            compose.onNodeWithText(text(it)).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.sort_author)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sort_title)).performClick()

        compose.onNodeWithText(text(R.string.filter_format)).performClick()
        listOf(R.string.format_epub, R.string.format_pdf, R.string.format_comics).forEach {
            compose.onNodeWithText(text(it)).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.format_audio)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.format_pdf)).performClick()

        compose.onNodeWithText(text(R.string.filter_status)).performClick()
        listOf(R.string.status_unread, R.string.status_reading, R.string.status_read).forEach {
            compose.onNodeWithText(text(it)).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.status_want_to_read)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.status_abandoned)).assertDoesNotExist()

        assertThat(events).containsExactly(BookSort.Title, FormatFilter.Pdf).inOrder()
    }

    private fun show(state: BookListUiState = BookListUiState(total = 721)) {
        compose.setContent {
            LirelleTheme {
                SortFilterBar(
                    state = state,
                    onSort = { events += it },
                    onToggleFormat = { events += it },
                    onToggleStatus = { events += it },
                    onReadAlongOnly = { events += "readAlong=$it" },
                    onPresentation = { events += it },
                )
            }
        }
    }

    @Test
    fun `shows the current sort with its direction and the number of books`() {
        show()

        compose.onNodeWithContentDescription(text(R.string.sort_descending, text(R.string.sort_added)))
            .assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.books_count, 721, 721)).assertIsDisplayed()
    }

    @Test
    fun `picking a sort reports it`() {
        show()

        compose.onNodeWithContentDescription(text(R.string.sort_descending, text(R.string.sort_added))).performClick()
        compose.onNodeWithText(text(R.string.sort_series)).performClick()

        assertThat(events).containsExactly(BookSort.Series)
    }

    @Test
    fun `picking a format and a status reports them`() {
        show()

        compose.onNodeWithText(text(R.string.filter_format)).performClick()
        compose.onNodeWithText(text(R.string.format_comics)).performClick()
        compose.onNodeWithText(text(R.string.filter_status)).performClick()
        compose.onNodeWithText(text(R.string.status_reading)).performClick()

        assertThat(events).containsExactly(FormatFilter.Comics, StatusFilter.Reading).inOrder()
    }

    @Test
    fun `the read-along chip toggles`() {
        show()

        compose.onNodeWithText(text(R.string.filter_read_along)).performClick()

        assertThat(events).containsExactly("readAlong=true")
    }

    @Test
    fun `the presentation button offers the other presentation`() {
        show(BookListUiState(presentation = Presentation.Grid))

        compose.onNodeWithContentDescription(text(R.string.view_as_list)).performClick()

        assertThat(events).containsExactly(Presentation.List)
    }

    @Test
    fun `an ascending sort says so`() {
        val request = BookListRequest(BookSort.Title, SortDirection.Ascending, BookFilters())
        show(BookListUiState(request = request))

        compose.onNodeWithContentDescription(text(R.string.sort_ascending, text(R.string.sort_title)))
            .assertIsDisplayed()
    }

    @Test
    fun `an active filter is marked with a tick, not by colour alone`() {
        val filters = BookFilters(formats = setOf(FormatFilter.Audio), readAlongOnly = true)
        show(BookListUiState(request = BookListRequest(filters = filters)))

        // A chip is one node for accessibility; its tick is found inside it.
        fun ticks(label: Int) = compose.onAllNodes(
            hasTestTag(FilterTickTag) and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(text(label)))),
            useUnmergedTree = true,
        )
        compose.onNodeWithText(text(R.string.filter_format)).assertIsSelected()
        compose.onNodeWithText(text(R.string.filter_read_along)).assertIsSelected()
        compose.onNodeWithText(text(R.string.filter_status)).assertIsNotSelected()
        ticks(R.string.filter_format).assertCountEquals(1)
        ticks(R.string.filter_read_along).assertCountEquals(1)
        ticks(R.string.filter_status).assertCountEquals(0)
        compose.onAllNodesWithTag(FilterTickTag, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `without filters nothing is ticked`() {
        show()

        compose.onAllNodesWithTag(FilterTickTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the sort is a control, not an active filter`() {
        show()

        val sort = compose.onNodeWithContentDescription(text(R.string.sort_descending, text(R.string.sort_added)))
        sort.assert(SemanticsMatcher("is not selected") { it.config.getOrNull(SemanticsProperties.Selected) != true })
        sort.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithText(text(R.string.sort_added), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(SortDirectionTag, useUnmergedTree = true).assertIsDisplayed()
    }
}
