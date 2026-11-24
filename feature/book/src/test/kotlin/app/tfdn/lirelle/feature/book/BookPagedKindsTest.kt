package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.PagedLabelTag
import app.tfdn.lirelle.core.ui.R as UiR
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config

private val Document = BookId("14")

/**
 * The page of a book read by pages that is not a comic, a PDF or a DjVu: its own chip, and for the rest what
 * the page of a comic has: the actions of a text, which open the page reader, and the page it is at.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookPagedKindsTest(private val format: String, private val kind: PagedKind, private val chip: Int) {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val read = mutableListOf<Pair<BookId, Boolean>>()
    private val readByPages = mutableListOf<Pair<BookId, Boolean>>()

    private val document = samplePagedDetail(format).copy(readState = null)
    private val atPage12 = BookReading(25, lastReadAt = null, totalSeconds = 600, sessionCount = 1, page = 12)

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun spoken(name: Int, status: Int? = null, vararg args: Any) = context.spokenAction(name, status, *args)

    private fun show(book: BookDetail, reading: BookReading? = null) {
        compose.setContent {
            LirelleTheme {
                BookContent(
                    state = BookUiState(Loadable.Ready(book), reading),
                    onBack = {},
                    onRetry = {},
                    onRefresh = {},
                    onOpenBook = {},
                    onOpenAuthor = {},
                    onOpenGenre = {},
                    onRead = { id, fromStart -> read += id to fromStart },
                    onListen = { _, _ -> },
                    onNarrate = {},
                    onReadComic = { id, fromStart -> readByPages += id to fromStart },
                )
            }
        }
    }

    private val inTheChip = hasParent(hasTestTag(PagedLabelTag))

    private fun mainAction() = compose.onNodeWithTag(MainActionTag).performScrollTo()

    @Test
    fun `the book is of its kind`() {
        assertThat(document.pagedKind).isEqualTo(kind)
    }

    @Test
    fun `it says what it is under its title, and not that it is a comic`() {
        show(document)

        compose.onAllNodesWithTag(PagedLabelTag).assertCountEquals(1)
        compose.onNodeWithTag(PagedLabelTag).performScrollTo().assertIsDisplayed()
        // The page names the format of its files further down too: the chip is the one looked at.
        compose.onNode(hasText(text(chip)) and inTheChip).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.media_comic)).assertDoesNotExist()
    }

    @Test
    fun `never opened, it starts being read in the page reader`() {
        show(document)

        mainAction()
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_start_spoken))
            .assertIsEnabled()
            .performClick()

        assertThat(readByPages).containsExactly(Document to false)
        assertThat(read).isEmpty()
    }

    @Test
    fun `being read, it goes on there, and the page it is at is under the bar of its reading`() {
        show(document.copy(readState = ReadState.Reading), atPage12)

        compose.onNodeWithTag(ReadingPageTag).performScrollTo().assertIsDisplayed()
            .assertTextEquals(text(R.string.reading_page, 12))
        mainAction()
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_resume_percent_spoken, 25))
            .performClick()

        assertThat(readByPages).containsExactly(Document to false)
        assertThat(read).isEmpty()
    }

    @Test
    fun `marked as read, it is read again from its first page`() {
        show(document.copy(readState = ReadState.Read), atPage12)

        mainAction()
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_read_again_spoken))
            .performClick()

        assertThat(readByPages).containsExactly(Document to true)
        assertThat(read).isEmpty()
    }

    @Test
    fun `its download is not offered`() {
        show(document)

        compose.onNodeWithText(text(R.string.action_download)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a comic keeps its own chip`() {
        show(sampleComicDetail())

        compose.onNodeWithTag(PagedLabelTag).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(text(UiR.string.media_comic)) and inTheChip).assertIsDisplayed()
        compose.onNode(hasText(text(chip)) and inTheChip).assertDoesNotExist()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("PDF", PagedKind.Pdf, UiR.string.media_pdf),
            arrayOf("DJVU", PagedKind.Djvu, UiR.string.media_djvu),
        )
    }
}
