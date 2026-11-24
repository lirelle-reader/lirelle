package app.tfdn.lirelle.feature.comics.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.feature.comics.ComicFailure
import app.tfdn.lirelle.feature.comics.ComicOpeningTag
import app.tfdn.lirelle.feature.comics.ComicPhase
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.page.ComicSpreadTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the screen shows while the album opens, when it could not be opened, and once it is open. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicReaderPhasesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val screen = ScreenUnderTest(ComicReaderUiState())

    @Test
    fun `while the album opens, a progress mark and a way back`() {
        show()

        compose.onNodeWithTag(ComicOpeningTag).assertExists()
        compose.onNodeWithTag(ComicSpreadTag).assertDoesNotExist()
        compose.onNodeWithContentDescription("Reading settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(screen.actions.calls).containsExactly("leave")
    }

    @Test
    fun `an album that could not be loaded says why, and can be tried again or left`() {
        screen.state = ComicReaderUiState(phase = ComicPhase.Failed(ComicFailure.Load(ApiError.ServerUnreachable)))
        show()

        compose.onNodeWithText("Could not reach the server. Check your connection.").assertExists()
        compose.onNodeWithTag(ComicOpeningTag).assertDoesNotExist()
        compose.onNodeWithText("Try again").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(screen.actions.calls).containsExactly("retryOpening", "leave").inOrder()
    }

    @Test
    fun `a book that is gone cannot be tried again`() {
        screen.state = ComicReaderUiState(phase = ComicPhase.Failed(ComicFailure.Load(ApiError.NotFound)))
        show()

        compose.onNodeWithText("This book is no longer available.").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a book without a comic says so`() {
        screen.state = ComicReaderUiState(phase = ComicPhase.Failed(ComicFailure.NoComic))
        show()

        compose.onNodeWithText("This book has no comic to read.").assertExists()
        compose.onNodeWithText("Try again").performClick()

        assertThat(screen.actions.calls).containsExactly("retryOpening")
    }

    @Test
    fun `an open album shows its pages`() {
        screen.state = openAlbum()
        show()

        compose.onNodeWithTag(ComicSpreadTag).assertExists()
        compose.onNodeWithTag(ComicOpeningTag).assertDoesNotExist()
    }

    @Test
    fun `back leaves, whatever the phase`() {
        show()
        back()
        screen.state = openAlbum(barsShown = false)
        compose.waitForIdle()
        back()

        assertThat(screen.actions.calls).containsExactly("leave", "leave")
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun show() = compose.setContent { screen.Content() }
}
