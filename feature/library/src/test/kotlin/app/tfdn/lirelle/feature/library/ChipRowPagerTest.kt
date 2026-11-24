package app.tfdn.lirelle.feature.library

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.feature.library.books.BookListUiState
import app.tfdn.lirelle.feature.library.books.ChipRowTag
import app.tfdn.lirelle.feature.library.books.SortFilterBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The chips scroll sideways inside a page of the Library, which itself swipes sideways between
 * tabs. A swipe that starts on the chips only ever moves the chips; anywhere else it changes tab.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the chips must be wider than the screen.
class ChipRowPagerTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "fr-w320dp-h640dp", fontScale = 2.0f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show() {
        compose.setContent {
            LirelleTheme {
                LibraryScreen { tab -> if (tab == LibraryTab.Books) BooksPage() else Text("page of ${tab.name}") }
            }
        }
    }

    /** As in the book list: the bar is the first item of a list that scrolls up and down. */
    @Composable
    private fun BooksPage() {
        val filters = BookFilters(
            formats = setOf(FormatFilter.Audio),
            statuses = setOf(StatusFilter.Reading),
            readAlongOnly = true,
        )
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                SortFilterBar(
                    state = BookListUiState(request = BookListRequest(filters = filters), total = 721),
                    onSort = {},
                    onToggleFormat = {},
                    onToggleStatus = {},
                    onReadAlongOnly = {},
                    onPresentation = {},
                )
            }
            items(ROW_COUNT) { index ->
                Box(modifier = Modifier.fillMaxWidth().height(120.dp).testTag("$RowTag$index"))
            }
        }
    }

    private fun lastChip() = compose.onNodeWithText(context.getString(R.string.filter_read_along))

    @Test
    fun `swiping the chips past their end stays on the tab`() {
        show()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        assertThat(lastChip().getUnclippedBoundsInRoot().right).isGreaterThan(screen.right)

        repeat(SWIPES) {
            compose.onNodeWithTag(ChipRowTag).performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }

        // The chips went to their end, and what was left of the swipes went nowhere.
        assertThat(lastChip().getUnclippedBoundsInRoot().right).isLessThan(screen.right)
        lastChip().assertIsDisplayed()
        compose.onNodeWithTag("tab-Books").assertIsSelected()
    }

    @Test
    fun `swiping below the chips moves to the next tab`() {
        show()

        compose.onNodeWithTag("${RowTag}0").performTouchInput { swipeLeft() }

        compose.onNodeWithTag("tab-Series").assertIsSelected()
        compose.onNodeWithText("page of Series").assertIsDisplayed()
    }

    @Test
    fun `swiping up from the chips scrolls the list`() {
        show()
        val before = firstRowTop()

        compose.onNodeWithTag(ChipRowTag).performTouchInput { swipeUp(startY = centerY, endY = centerY - LIST_DRAG_PX) }
        compose.waitForIdle()

        assertThat(firstRowTop()).isLessThan(before)
        compose.onNodeWithTag("tab-Books").assertIsSelected()
    }

    /** Where the first row under the bar is, in pixels; far above the screen once it is scrolled away. */
    private fun firstRowTop(): Float =
        compose.onAllNodesWithTag("${RowTag}0").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
            ?: Float.NEGATIVE_INFINITY

    private companion object {
        const val RowTag = "row-"
        const val ROW_COUNT = 10
        const val SWIPES = 4
        const val LIST_DRAG_PX = 100f
    }
}
