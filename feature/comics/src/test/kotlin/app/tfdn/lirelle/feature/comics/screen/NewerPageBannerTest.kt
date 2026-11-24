package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.feature.comics.ComicBottomBarTag
import app.tfdn.lirelle.feature.comics.NewerPageBannerTag
import app.tfdn.lirelle.feature.comics.page.drawn
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The offer of a page read further on another device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class NewerPageBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenUnderTest()

    @Test
    fun `nothing is offered without a newer page`() {
        compose.setContent { screen.Content() }

        compose.onNodeWithTag(NewerPageBannerTag).assertDoesNotExist()
    }

    @Test
    fun `the newer page is offered above the bottom bar, with its two answers`() {
        screen.state = openAlbum().copy(newerPage = 31)
        compose.setContent { screen.Content() }

        compose.onNodeWithText("Page 31 read on another device").assertExists()
        val banner = compose.onNodeWithTag(NewerPageBannerTag).drawn()
        assertThat(banner.bottom).isAtMost(compose.onNodeWithTag(ComicBottomBarTag).drawn().top)

        compose.onNodeWithText("Go there").performClick()
        compose.onNodeWithText("Ignore").performClick()

        assertThat(screen.actions.calls).containsExactly("acceptNewer", "ignoreNewer").inOrder()
    }

    @Test
    fun `it shows with the bars hidden, at the bottom of the screen`() {
        screen.state = openAlbum(barsShown = false).copy(newerPage = 31)
        compose.setContent { screen.Content() }

        compose.onNodeWithTag(ComicBottomBarTag).assertDoesNotExist()
        compose.onNodeWithText("Page 31 read on another device").assertExists()
        val banner = compose.onNodeWithTag(NewerPageBannerTag).drawn()
        assertThat(banner.bottom).isEqualTo(compose.onRoot().fetchSemanticsNode().size.height.toFloat())
    }
}
