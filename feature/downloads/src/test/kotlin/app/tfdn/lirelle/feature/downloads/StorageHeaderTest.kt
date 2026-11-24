package app.tfdn.lirelle.feature.downloads

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ui.fileSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class StorageHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(storage: StorageUi) {
        compose.setContent { LirelleTheme { StorageHeader(storage) } }
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun styleOf(text: String): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().layoutInput.style
    }

    /** Quiet: not a second title of the page, which is in the serif of the headlines. */
    private fun assertQuiet(text: String) {
        val style = styleOf(text)
        assertThat(style.fontFamily).isNotEqualTo(FontFamily.Serif)
        assertThat(style.fontSize.value).isAtMost(QuietSizeSp)
    }

    @Test
    fun `with books downloaded it says their size and their number, quietly, and the free space`() {
        show(StorageUi(111 * Mb, bookCount = 1, freeBytes = 41 * Gb, totalBytes = 128 * Gb, queued = true))

        assertQuiet(string(R.string.downloads_storage_used, context.fileSize(111 * Mb)))
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.downloads_book_count, 1, 1))
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_storage_free, context.fileSize(41 * Gb))).assertIsDisplayed()
        compose.onNodeWithTag(StorageHeaderTag).assertIsDisplayed()
    }

    @Test
    fun `with nothing complete and a download in progress it says nothing is downloaded yet`() {
        show(StorageUi(11 * Mb, bookCount = 0, freeBytes = 41 * Gb, totalBytes = 128 * Gb, queued = true))

        assertQuiet(string(R.string.downloads_storage_none_yet))
        compose.onNodeWithText(string(R.string.downloads_storage_none)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.downloads_storage_used, context.fileSize(11 * Mb))).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.downloads_storage_free, context.fileSize(41 * Gb))).assertIsDisplayed()
        compose.onNodeWithTag(StorageBarTag).assert(
            hasContentDescription(
                string(
                    R.string.downloads_storage_bar,
                    context.fileSize(11 * Mb),
                    context.fileSize(41 * Gb),
                    context.fileSize(128 * Gb),
                ),
            ),
        )
    }

    @Test
    fun `with nothing complete and nothing asked it says there is no download`() {
        show(StorageUi(0, bookCount = 0, freeBytes = 41 * Gb, totalBytes = 128 * Gb, queued = false))

        assertQuiet(string(R.string.downloads_storage_none))
        compose.onNodeWithText(string(R.string.downloads_storage_none_yet)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.downloads_storage_free, context.fileSize(41 * Gb))).assertIsDisplayed()
    }

    private companion object {
        /** The size of the titles of the sections, the largest a text of the header may have. */
        const val QuietSizeSp = 16f
    }
}
