package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.feature.comics.AlbumBannerTag
import app.tfdn.lirelle.feature.comics.ComicBottomBarTag
import app.tfdn.lirelle.feature.comics.NewerPageBannerTag
import app.tfdn.lirelle.feature.comics.PanelReading
import app.tfdn.lirelle.feature.comics.page.drawn
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Sentence = "Few pages of this album can be cut into panels. They are shown whole."

/** What an album that lends itself badly to reading panel by panel says, and its two answers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AlbumBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenUnderTest(told())

    private fun told(barsShown: Boolean = true) = openAlbum(barsShown = barsShown)
        .copy(panels = PanelReading(offered = true, on = true, albumBanner = true))

    @Test
    fun `nothing is said until the state says so`() {
        screen.state = openAlbum().copy(panels = PanelReading(offered = true, on = true))
        compose.setContent { screen.Content() }

        compose.onNodeWithTag(AlbumBannerTag).assertDoesNotExist()
    }

    @Test
    fun `it is said above the bottom bar, where the newer page is offered`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithText(Sentence).assertExists()
        val banner = compose.onNodeWithTag(AlbumBannerTag).drawn()
        assertThat(banner.bottom).isAtMost(compose.onNodeWithTag(ComicBottomBarTag).drawn().top)
        listOf("Turn panel by panel off", "OK").forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp) }
    }

    @Test
    fun `with the bars hidden it is at the bottom of the screen`() {
        screen.state = told(barsShown = false)
        compose.setContent { screen.Content() }

        val banner = compose.onNodeWithTag(AlbumBannerTag).drawn()
        assertThat(banner.bottom).isEqualTo(compose.onRoot().fetchSemanticsNode().size.height.toFloat())
    }

    @Test
    fun `OK closes it and leaves the mode as it is`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithText("OK").performClick()

        assertThat(screen.actions.calls).containsExactly("closeBanner")
    }

    @Test
    fun `turning the mode off turns it off for the series, then closes it`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Turn panel by panel off").performClick()

        assertThat(screen.actions.calls).containsExactly("panels false", "closeBanner").inOrder()
    }

    @Test
    fun `a newer page has the place, and the album waits for its answer`() {
        screen.state = told().copy(newerPage = 31)
        compose.setContent { screen.Content() }
        compose.onNodeWithTag(NewerPageBannerTag).assertExists()
        compose.onNodeWithTag(AlbumBannerTag).assertDoesNotExist()

        screen.state = told()
        compose.waitForIdle()

        compose.onNodeWithTag(NewerPageBannerTag).assertDoesNotExist()
        compose.onNodeWithTag(AlbumBannerTag).assertExists()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `it is said in French`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Peu de planches de cet album se découpent en cases. Elles s’affichent entières.")
            .assertExists()
        compose.onNodeWithText("Désactiver le case par case").assertExists()
        compose.onNodeWithText("OK").assertExists()
    }
}
