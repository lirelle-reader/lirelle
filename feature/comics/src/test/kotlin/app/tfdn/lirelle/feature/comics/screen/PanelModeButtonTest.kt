package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.feature.comics.ComicPanelModeButtonTag
import app.tfdn.lirelle.feature.comics.ComicPhase
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.ComicSettingsButtonTag
import app.tfdn.lirelle.feature.comics.PanelReading
import app.tfdn.lirelle.feature.comics.page.drawn
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The button of reading panel by panel, in the top bar, and what is told the first time it is turned on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class PanelModeButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenUnderTest(offered())

    private fun offered(on: Boolean = false, notice: Boolean = false) =
        openAlbum().copy(panels = PanelReading(offered = true, on = on, notice = notice))

    @Test
    fun `an album that is not offered the mode has no button, nor has one that is not open`() {
        screen.state = openAlbum().copy(kind = PagedKind.Pdf)
        compose.setContent { screen.Content() }
        compose.onNodeWithTag(ComicSettingsButtonTag).assertExists()
        compose.onNodeWithTag(ComicPanelModeButtonTag).assertDoesNotExist()

        screen.state = ComicReaderUiState(phase = ComicPhase.Loading, title = Title)
        compose.waitForIdle()

        compose.onNodeWithTag(ComicPanelModeButtonTag).assertDoesNotExist()
    }

    @Test
    fun `the button is left of the settings, in a target of 48 dp`() {
        compose.setContent { screen.Content() }

        val button = compose.onNodeWithTag(ComicPanelModeButtonTag)
        button.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val settings = compose.onNodeWithTag(ComicSettingsButtonTag).drawn()
        assertThat(button.drawn().right).isAtMost(settings.left)
        assertThat(button.drawn().top).isEqualTo(settings.top)
    }

    @Test
    fun `it says whether the series is read panel by panel, and asks for the other state`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithContentDescription("Panel by panel, off").assertIsOff().performClick()
        assertThat(screen.actions.calls).containsExactly("panels true")

        screen.state = offered(on = true)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Panel by panel, off").assertDoesNotExist()
        compose.onNodeWithContentDescription("Panel by panel, on").assertIsOn().performClick()
        assertThat(screen.actions.calls).containsExactly("panels true", "panels false").inOrder()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `it says so in French`() {
        compose.setContent { screen.Content() }
        compose.onNodeWithContentDescription("Case par case, désactivé").assertExists()

        screen.state = offered(on = true)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Case par case, activé").assertExists()
    }

    @Test
    fun `nothing is told until the state says so`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Reading panel by panel").assertDoesNotExist()
    }

    @Test
    fun `the notice says what the mode is and where it falls short, and its button closes it`() {
        screen.state = offered(notice = true)
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Reading panel by panel").assertExists()
        compose.onNodeWithText("Panels are found automatically, page by page.").assertExists()
        compose.onNodeWithText(
            "Some pages, and some albums, do not lend themselves to it: the page, or a part of it, is then " +
                "shown whole.",
        ).assertExists()

        compose.onNodeWithText("Got it").performClick()

        assertThat(screen.actions.calls).containsExactly("closeNotice")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the notice in French`() {
        screen.state = offered(notice = true)
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Lecture case par case").assertExists()
        compose.onNodeWithText("Les cases sont repérées automatiquement, planche par planche.").assertExists()
        compose.onNodeWithText(
            "Certaines planches, et certains albums, ne s’y prêtent pas : la planche ou une partie de la " +
                "planche s’affiche alors entière.",
        ).assertExists()
        compose.onNodeWithText("Compris").assertExists()
    }
}
