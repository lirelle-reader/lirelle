package app.tfdn.lirelle.shell

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ui.fileSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.tfdn.lirelle.core.ui.R as UiR

private const val Used = 3_400_000_000L
private const val Free = 41_200_000_000L

/** The Downloads page of the settings: its title, its rows and the question it asks before deleting. */
@RunWith(RobolectricTestRunner::class)
class DownloadsSettingsPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var state by mutableStateOf(DownloadsSettingsState(false, books = 5, usedBytes = Used, freeBytes = Free))
    private val allowed = mutableListOf<Boolean>()
    private var deletions = 0

    private fun string(id: Int) = context.getString(id)

    private fun size(bytes: Long) = context.fileSize(bytes)

    private fun show(onBack: (() -> Unit)? = {}) {
        compose.setContent {
            LirelleTheme {
                DownloadsSettingsPage(
                    state = state,
                    onAllowMetered = { allowed += it },
                    onDeleteAll = { deletions++ },
                    onBack = onBack,
                )
            }
        }
    }

    private fun switch() = compose.onNode(
        hasText(string(R.string.more_downloads_metered)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
    )

    @Test
    fun `the title is the heading of the page, said once`() {
        show()

        compose.onNodeWithText(string(R.string.more_downloads))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `on its own the page has an arrow that goes back`() {
        var backs = 0
        show(onBack = { backs++ })

        compose.onNodeWithContentDescription(string(UiR.string.settings_back)).performClick()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `in a pane the page has its title and no arrow`() {
        show(onBack = null)

        compose.onNodeWithText(string(R.string.more_downloads)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(UiR.string.settings_back)).assertDoesNotExist()
        switch().assertIsOff()
    }

    @Test
    fun `mobile network off says that downloads wait for Wi-Fi, in one switch with its label`() {
        show()

        switch().assertIsOff().assert(hasText(string(R.string.more_downloads_metered_off)))
    }

    @Test
    fun `mobile network on says that downloads use any network`() {
        state = state.copy(allowMetered = true)
        show()

        switch().assertIsOn().assert(hasText(string(R.string.more_downloads_metered_on)))
    }

    @Test
    fun `tapping the row asks for the other state`() {
        show()

        switch().performClick()
        state = state.copy(allowMetered = true)
        switch().performClick()

        assertThat(allowed).containsExactly(true, false).inOrder()
    }

    @Test
    fun `the space used reads as one row with the books, the room left and the size`() {
        show()

        val detail = context.resources.getQuantityString(R.plurals.more_downloads_space_detail, 5, 5, size(Free))
        compose.onNode(hasText(string(R.string.more_downloads_space)) and hasText(detail) and hasText(size(Used)))
            .assertIsDisplayed()
    }

    @Test
    fun `nothing can be deleted while nothing is downloaded`() {
        state = state.copy(books = 0, usedBytes = 0)
        show()

        compose.onNodeWithText(string(R.string.more_downloads_delete_all)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.more_downloads_space)).assertIsDisplayed()
    }

    @Test
    fun `deleting everything asks first, naming the books and the size`() {
        show()

        compose.onNodeWithText(string(R.string.more_downloads_delete_all)).performClick()

        val message = context.resources.getQuantityString(R.plurals.more_downloads_delete_all_message, 5, 5, size(Used))
        compose.onNodeWithText(string(R.string.more_downloads_delete_all_title)).assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()
        assertThat(deletions).isEqualTo(0)
    }

    @Test
    fun `confirming deletes everything and closes the question`() {
        show()
        compose.onNodeWithText(string(R.string.more_downloads_delete_all)).performClick()

        compose.onNodeWithText(string(R.string.more_downloads_delete_all_confirm)).performClick()

        assertThat(deletions).isEqualTo(1)
        compose.onNodeWithText(string(R.string.more_downloads_delete_all_title)).assertDoesNotExist()
    }

    @Test
    fun `cancelling deletes nothing`() {
        show()
        compose.onNodeWithText(string(R.string.more_downloads_delete_all)).performClick()

        compose.onNodeWithText(string(R.string.more_downloads_cancel)).performClick()

        assertThat(deletions).isEqualTo(0)
        compose.onNodeWithText(string(R.string.more_downloads_delete_all_title)).assertDoesNotExist()
    }
}
