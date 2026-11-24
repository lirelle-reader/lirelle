package app.tfdn.lirelle.feature.reader

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screen on a real view model: what it reports to it, and what it does of its events. */
@RunWith(RobolectricTestRunner::class)
class ReaderScreenHostTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private var left = 0
    private val openedBooks = mutableListOf<BookId>()
    private var back: OnBackPressedDispatcher? = null

    private fun show(): ReaderViewModel {
        val viewModel = ReaderHarness(TestScope(mainRule.dispatcher), folder.root).viewModel()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LirelleTheme {
                ReaderScreen(
                    viewModel = viewModel,
                    onLeave = { left++ },
                    onOpenBook = { openedBooks += it },
                    sheets = { state, _ -> Text("sheets of ${state.title}, ${state.openSheet}") },
                    endPanel = { state, _ -> Text("end panel: ${state.endPanel != null}") },
                )
            }
        }
        compose.waitForIdle()
        return viewModel
    }

    @Test
    fun `the back arrow's event leaves the reader, once`() {
        val viewModel = show()

        viewModel.leave()
        compose.waitForIdle()

        assertThat(left).isEqualTo(1)
        assertThat(openedBooks).isEmpty()
    }

    @Test
    fun `the system's back closes what is open, then leaves the reader`() {
        val viewModel = show()
        viewModel.showSheet(ReaderSheet.Contents)

        compose.runOnUiThread { back?.onBackPressed() }
        compose.waitForIdle()
        assertThat(viewModel.state.value.openSheet).isNull()
        assertThat(left).isEqualTo(0)

        compose.runOnUiThread { back?.onBackPressed() }
        compose.waitForIdle()
        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `the slots are given the state and the actions`() {
        val viewModel = show()
        val title = viewModel.state.value.title
        assertThat(title).isNotEmpty()
        compose.onNodeWithText("sheets of $title, null").assertExists()
        compose.onNodeWithText("end panel: false").assertExists()

        viewModel.showSheet(ReaderSheet.Settings)

        compose.onNodeWithText("sheets of $title, Settings").assertExists()
    }

    @Test
    @Config(qualifiers = "night")
    fun `a dark device is reported, and the system theme follows it`() {
        val viewModel = show()

        assertThat(viewModel.state.value.colours.isDark).isTrue()
    }

    @Test
    fun `a light device gives a light page`() {
        val viewModel = show()

        assertThat(viewModel.state.value.colours.isDark).isFalse()
    }
}
