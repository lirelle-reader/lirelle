package app.tfdn.lirelle.feature.downloads

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ui.fileSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DeleteSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val confirmed = mutableListOf<Set<DownloadFormat>>()
    private var dismissals = 0

    private val all = DeleteSheetUi(
        bookId = BookId("2"),
        title = "Red Mars",
        bytes = 1_926 * Mb,
        options = listOf(
            DeleteOption(DownloadFormat.Text, 3 * Mb),
            DeleteOption(DownloadFormat.Audiobook, 982 * Mb),
            DeleteOption(DownloadFormat.Narration, 941 * Mb),
        ),
    )

    private fun show(sheet: DeleteSheetUi = all) {
        compose.setContent {
            LirelleTheme { DeleteSheet(sheet, onConfirm = { confirmed += it }, onDismiss = { dismissals++ }) }
        }
    }

    private fun option(format: DownloadFormat) = compose.onNodeWithTag(deleteOptionTag(format))

    @Test
    fun `the sheet names the book and each format with its size, nothing checked`() {
        show()

        compose.onNodeWithText(context.getString(R.string.downloads_delete)).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.downloads_sheet_subtitle, "Red Mars", context.fileSize(1_926 * Mb)),
        ).assertIsDisplayed()
        all.options.forEach { (format, bytes) ->
            option(format).assertIsOff()
            compose.onNode(
                hasText(context.fileSize(bytes)) and hasAnyAncestor(hasTestTag(deleteOptionTag(format))),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.downloads_sheet_note)).assertIsDisplayed()
    }

    @Test
    fun `a text read by pages is named by its file, the other formats as they are`() {
        val options = listOf(
            DeleteOption(DownloadFormat.Text, 9 * Mb, TextName.Cbz),
            DeleteOption(DownloadFormat.Audiobook, 982 * Mb, TextName.Cbz),
        )
        show(all.copy(options = options))

        fun named(word: String, format: DownloadFormat) = compose.onNode(
            hasText(word) and hasAnyAncestor(hasTestTag(deleteOptionTag(format))),
            useUnmergedTree = true,
        )
        named("CBZ", DownloadFormat.Text).assertIsDisplayed()
        named("Audiobook", DownloadFormat.Audiobook).assertIsDisplayed()
        named("Text", DownloadFormat.Text).assertDoesNotExist()
    }

    @Test
    fun `nothing checked, there is nothing to delete`() {
        show()

        compose.onNodeWithTag(DeleteConfirmTag).assertIsNotEnabled()
            .assertTextEquals(context.getString(R.string.downloads_sheet_confirm_nothing))
    }

    @Test
    fun `the text is kept while the narration stays, and says why`() {
        show()

        option(DownloadFormat.Text).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.downloads_sheet_text_kept)).assertIsDisplayed()
    }

    @Test
    fun `the text can go once the narration goes`() {
        show()

        option(DownloadFormat.Narration).performClick()

        option(DownloadFormat.Text).assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.downloads_sheet_text_kept)).assertDoesNotExist()
    }

    @Test
    fun `keeping the narration after all keeps the text too`() {
        show()
        option(DownloadFormat.Narration).performClick()
        option(DownloadFormat.Text).performClick()

        option(DownloadFormat.Narration).performClick()

        option(DownloadFormat.Text).assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun `without a narration the text is free to go`() {
        show(all.copy(options = all.options.filterNot { it.format == DownloadFormat.Narration }))

        option(DownloadFormat.Text).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.downloads_sheet_text_kept)).assertDoesNotExist()
    }

    @Test
    fun `the button says the space freed and deletes what is checked`() {
        show()
        option(DownloadFormat.Audiobook).performClick()
        option(DownloadFormat.Narration).performClick()

        compose.onNodeWithTag(DeleteConfirmTag)
            .assertTextEquals(context.getString(R.string.downloads_sheet_confirm, context.fileSize(1_923 * Mb)))
            .performClick()

        assertThat(confirmed).containsExactly(setOf(DownloadFormat.Audiobook, DownloadFormat.Narration))
    }

    @Test
    fun `cancel closes the sheet and deletes nothing`() {
        show()
        option(DownloadFormat.Audiobook).performClick()

        compose.onNodeWithTag(DeleteCancelTag).performClick()
        compose.waitForIdle()

        assertThat(dismissals).isEqualTo(1)
        assertThat(confirmed).isEmpty()
    }
}
