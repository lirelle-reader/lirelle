package app.tfdn.lirelle.feature.history

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h3000dp-xhdpi") // Tall enough to compose the whole lazy list.
class HistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(
        state: HistoryUiState,
        onSelectPeriod: (HistoryPeriod) -> Unit = {},
        onToggleOutcome: (ReadOutcome) -> Unit = {},
        onOpenRead: (ReadId, BookId?) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            LirelleTheme {
                HistoryScreen(
                    state = state,
                    onSelectPeriod = onSelectPeriod,
                    onToggleOutcome = onToggleOutcome,
                    onLoadMore = {},
                    onOpenRead = onOpenRead,
                    onBack = {},
                    onRetrySummary = {},
                    onRetryLog = {},
                )
            }
        }
    }

    @Test
    fun `shows the summary, the period chips and a row`() {
        show(historyState())

        compose.onNodeWithText(context.getString(R.string.history_goal, 42, 50)).assertIsDisplayed()
        compose.onNodeWithText("418 h").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.history_period_all)).assertIsDisplayed()
        compose.onNodeWithText("2026").assertIsDisplayed()
        compose.onNodeWithText("A Wizard of Earthsea").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a year chip selects that period`() {
        var picked: HistoryPeriod? = null
        show(historyState(), onSelectPeriod = { picked = it })

        compose.onNodeWithText("2025").performClick()

        assertThat(picked).isEqualTo(HistoryPeriod(2025))
    }

    @Test
    fun `an outcome chip toggles that outcome`() {
        var toggled: ReadOutcome? = null
        show(historyState(), onToggleOutcome = { toggled = it })

        // The chip comes before any row that carries the same word in its pill.
        compose.onAllNodesWithText(context.getString(R.string.history_outcome_skimmed)).onFirst().performClick()

        assertThat(toggled).isEqualTo(ReadOutcome.Skimmed)
    }

    @Test
    fun `a row opens its read`() {
        var opened: Pair<ReadId, BookId?>? = null
        show(historyState(), onOpenRead = { id, book -> opened = id to book })

        compose.onNodeWithText("A Wizard of Earthsea").performScrollTo().performClick()

        assertThat(opened).isEqualTo(ReadId("1") to BookId("book-1"))
    }

    @Test
    fun `an empty log says so`() {
        show(historyState(log = LogUi.Empty))

        compose.onNodeWithText(context.getString(R.string.history_empty)).assertIsDisplayed()
    }

    @Test
    fun `an empty log under a filter says so`() {
        show(historyState(log = LogUi.Empty).copy(outcomes = setOf(ReadOutcome.Abandoned)))

        compose.onNodeWithText(context.getString(R.string.history_empty_filtered)).assertIsDisplayed()
    }

    @Test
    fun `without a summary or ratings there is no card and no stars`() {
        val capabilities = HistoryCapabilities.All.copy(summary = false, rating = false)
        show(historyState(capabilities = capabilities, summary = SummaryUi.Hidden))

        compose.onNodeWithText(context.getString(R.string.history_total_reads)).assertDoesNotExist()
        compose.onNodeWithText("A Wizard of Earthsea").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.history_rating_description, 4))
            .assertCountEquals(0)
    }

    @Test
    fun `with every capability off, the books read are listed by month, without chips or a summary`() {
        val records = listOf(
            finishedBook("0BK1", "First Tide", LocalDate.of(2026, 11, 21)),
            finishedBook("0BK2", "Lantern", LocalDate.of(2026, 11, 1)),
            finishedBook("0BK3", "Old Harbour", LocalDate.of(2026, 10, 5)),
        )
        val state = historyState(
            capabilities = FinishedBooksOnly,
            summary = SummaryUi.Hidden,
            // No month totals from such a source: the sections count their rows.
            log = LogUi.Loaded(sections(records, months = null), canLoadMore = false),
        ).copy(years = emptyList())

        show(state)

        listOf("First Tide", "Lantern", "Old Harbour").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        val reads = context.resources
        compose.onNodeWithText(reads.getQuantityString(R.plurals.history_reads_count, 2, 2)).assertIsDisplayed()
        compose.onNodeWithText(reads.getQuantityString(R.plurals.history_reads_count, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.history_total_reads)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.history_outcome_skimmed)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.history_outcome_abandoned)).assertDoesNotExist()
        compose.onAllNodesWithText("2025").assertCountEquals(0)
    }

    @Test
    fun `with ratings the rows show their stars`() {
        show(historyState())

        compose.onNodeWithText("A Wizard of Earthsea").performScrollTo()
        compose.onAllNodesWithContentDescription(context.getString(R.string.history_rating_description, 4))
            .fetchSemanticsNodes().let { assertThat(it).isNotEmpty() }
    }
}
