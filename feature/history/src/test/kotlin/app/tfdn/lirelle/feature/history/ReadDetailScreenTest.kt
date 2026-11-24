package app.tfdn.lirelle.feature.history

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h3000dp-xhdpi")
class ReadDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(
        state: ReadDetailUiState,
        onSetStars: (Int) -> Unit = {},
        onOpenBook: (BookId) -> Unit = {},
    ) {
        compose.setContent {
            LirelleTheme {
                ReadDetailScreen(
                    state = state,
                    onSetStars = onSetStars,
                    onOpenBook = onOpenBook,
                    onBack = {},
                    onRetryMarks = {},
                )
            }
        }
    }

    private fun text(id: Int) = context.getString(id)

    private fun starDescription(value: Int) = context.getString(R.string.read_detail_rate_star, value)

    @Test
    fun `shows the header, the stars, the metadata, the marks and the open button`() {
        show(detailState())

        compose.onNodeWithText("A Wizard of Earthsea").assertIsDisplayed()
        compose.onNodeWithContentDescription(starDescription(5)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.read_detail_rate_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.read_detail_time)).assertIsDisplayed()
        compose.onNodeWithText("9 h").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.read_detail_sessions)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.read_detail_highlights)).assertIsDisplayed()
        compose.onNodeWithText("To light a candle is to cast a shadow.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.read_detail_journal)).assertIsDisplayed()
        compose.onNodeWithText("The ending changed everything.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.read_detail_open_book)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a star reports its value`() {
        var stars: Int? = null
        show(detailState(), onSetStars = { stars = it })

        compose.onNodeWithContentDescription(starDescription(4)).performClick()

        assertThat(stars).isEqualTo(4)
    }

    @Test
    fun `the stars are disabled while saving`() {
        var stars: Int? = null
        show(detailState(rating = RatingUi.Editable(stars = 3, saving = true)), onSetStars = { stars = it })

        val star = compose.onNodeWithContentDescription(starDescription(4))
        star.assertIsNotEnabled()
        star.performClick()

        assertThat(stars).isNull()
    }

    @Test
    fun `the stars are enabled when idle`() {
        show(detailState())

        compose.onNodeWithContentDescription(starDescription(1)).assertIsEnabled()
    }

    @Test
    fun `the open button reports the book`() {
        var opened: BookId? = null
        show(detailState(), onOpenBook = { opened = it })

        compose.onNodeWithText(text(R.string.read_detail_open_book)).performScrollTo().performClick()

        assertThat(opened).isEqualTo(BookId("book-1"))
    }

    @Test
    fun `a read without a book has no open button`() {
        show(detailState(record = readRecord(1, "A Wizard of Earthsea").copy(bookId = null)))

        compose.onAllNodesWithText(text(R.string.read_detail_open_book)).assertCountEquals(0)
    }

    @Test
    fun `hidden marks show neither section`() {
        show(detailState(marks = MarksUi.Hidden))

        compose.onAllNodesWithText(text(R.string.read_detail_highlights)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.read_detail_journal)).assertCountEquals(0)
    }

    @Test
    fun `with every capability off, a read shows its day and opens its book, nothing more`() {
        show(
            detailState(
                record = finishedBook("0BK1", "First Tide", LocalDate.of(2026, 11, 21)),
                rating = RatingUi.Hidden,
                marks = MarksUi.Hidden,
                capabilities = FinishedBooksOnly,
            ),
        )

        compose.onNodeWithText("First Tide").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.read_detail_dates)).assertIsDisplayed()
        listOf(R.string.read_detail_time, R.string.read_detail_sessions, R.string.read_detail_read_number)
            .forEach { compose.onAllNodesWithText(text(it)).assertCountEquals(0) }
        compose.onAllNodesWithText(text(R.string.read_detail_rate_hint)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.read_detail_highlights)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.read_detail_open_book)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a hidden rating shows no stars`() {
        show(detailState(rating = RatingUi.Hidden))

        compose.onAllNodesWithContentDescription(starDescription(1)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.read_detail_rate_hint)).assertCountEquals(0)
    }
}
