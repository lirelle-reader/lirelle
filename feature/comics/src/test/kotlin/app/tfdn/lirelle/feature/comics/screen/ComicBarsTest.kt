package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.testing.laidOutTexts
import app.tfdn.lirelle.feature.comics.ComicBottomBarTag
import app.tfdn.lirelle.feature.comics.ComicSliderTag
import app.tfdn.lirelle.feature.comics.ComicTopBarTag
import app.tfdn.lirelle.feature.comics.SeriesLine
import app.tfdn.lirelle.feature.comics.page.viewport
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bars over the pages: when they show, and what they say. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicBarsTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenUnderTest()

    @Test
    fun `the bars show and hide as the state says, over a page that keeps the whole screen`() {
        compose.setContent { screen.Content() }
        compose.onNodeWithTag(ComicTopBarTag).assertExists()
        compose.onNodeWithTag(ComicBottomBarTag).assertExists()
        val page = compose.viewport()
        val root = compose.onRoot().fetchSemanticsNode().size
        assertThat(page.top).isEqualTo(0f)
        assertThat(page.height).isEqualTo(root.height.toFloat())

        screen.state = openAlbum(barsShown = false)
        compose.waitForIdle()

        compose.onNodeWithTag(ComicTopBarTag).assertDoesNotExist()
        compose.onNodeWithTag(ComicBottomBarTag).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertThat(compose.viewport()).isEqualTo(page)
    }

    @Test
    fun `the top bar holds back, the title on one line, the series and number, and the settings`() {
        screen.state = openAlbum().copy(title = "Quelque part entre les ombres, là où la lumière du matin ne va pas")
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Blacksad #1").assertExists()
        val title = compose.laidOutTexts().single { it.text.startsWith("Quelque part") }
        assertThat(title.lineCount).isEqualTo(1)
        assertThat(title.isEllipsized).isTrue()
        listOf("Back", "Reading settings").forEach {
            compose.onNodeWithContentDescription(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        assertThat(screen.actions.calls).containsExactly("leave")
    }

    @Test
    fun `a series without a number is named alone, and an album without a series has no such line`() {
        screen.state = openAlbum().copy(series = SeriesLine("Blacksad", null))
        compose.setContent { screen.Content() }
        compose.onNodeWithText("Blacksad").assertExists()

        screen.state = openAlbum().copy(series = null)
        compose.waitForIdle()

        compose.onNodeWithText("Blacksad").assertDoesNotExist()
        compose.onNodeWithText(Title).assertExists()
    }

    @Test
    fun `the bottom bar tells the page and how far it is in the album`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Page 12 of 48").assertExists()
        compose.onNodeWithText("25%").assertExists()
        val slider = compose.onNodeWithTag(ComicSliderTag).assertContentDescriptionEquals("Page")
        assertThat(slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
            .isEqualTo("12 of 48")
    }

    @Test
    fun `with two pages, both are told`() {
        // The cover alone, then the pages by pairs: the seventh spread shows the pages 12 and 13.
        screen.state = openAlbum(spreadIndex = 6, twoPages = true)
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Pages 12-13 of 48").assertExists()
        compose.onNodeWithText("27%").assertExists()
        val slider = compose.onNodeWithTag(ComicSliderTag).fetchSemanticsNode()
        assertThat(slider.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo("12-13 of 48")
    }

    @Test
    fun `while the slider is dragged, the bar tells the page it would go to`() {
        screen.state = openAlbum().copy(previewPage = 29)
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Page 30 of 48").assertExists()
        compose.onNodeWithText("62%").assertExists()
    }
}
