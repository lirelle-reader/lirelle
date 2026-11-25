package app.tfdn.lirelle.feature.comics.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.page.ComicSpreadTag
import app.tfdn.lirelle.feature.comics.strip.ComicStripTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The screen shows the pages of an album one way or the other: turned, or in a strip when it is read vertically. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Phone)
class ComicStripScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(screen: ScreenUnderTest) {
        compose.setContent { LirelleTheme { screen.Content() } }
        compose.waitForIdle()
    }

    @Test
    fun `an album read vertically is a strip, which tells the reader what it shows`() {
        val screen = ScreenUnderTest(openAlbum(settings = ComicSettings(direction = Direction.Vertical)))
        show(screen)

        compose.onNodeWithTag(ComicStripTag).assertExists()
        compose.onNodeWithTag(ComicSpreadTag).assertDoesNotExist()
        assertThat(screen.actions.calls.first()).startsWith("stripShows 11 ")
    }

    @Test
    fun `an album whose pages are turned has no strip`() {
        val screen = ScreenUnderTest(openAlbum())
        show(screen)

        compose.onNodeWithTag(ComicSpreadTag).assertExists()
        compose.onNodeWithTag(ComicStripTag).assertDoesNotExist()
        assertThat(screen.actions.calls.none { it.startsWith("strip") }).isTrue()
    }

    @Test
    fun `the direction changed to vertical while reading, the strip opens on the page read`() {
        val screen = ScreenUnderTest(openAlbum(spreadIndex = 20))
        show(screen)

        screen.state = screen.state.copy(settings = ComicSettings(direction = Direction.Vertical))
        compose.waitForIdle()

        assertThat(screen.actions.calls.first { it.startsWith("stripShows") }).startsWith("stripShows 20 ")
    }
}
