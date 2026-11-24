package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import coil3.request.transitionFactory
import coil3.transition.Transition
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Width = 200
private const val Height = 300

/**
 * How the picture of a page is asked of the image loader: with no fade, and once. The picture that stays under
 * a sharper one until that one is decoded is the one that was shown, not one asked again: its file is removed
 * as soon as the sharper one is told.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicPagePictureTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private var state by mutableStateOf(ComicReaderUiState())

    private fun show(page: PageState) {
        val settings = ComicSettings(background = Background.White)
        state = album(spreadIndex = 5, settings = settings, pages = mapOf(5 to page))
        compose.setContent {
            images.Provide { LirelleTheme { ComicSpreadView(state, RecordingNavigation(), onToggleBars = {}) } }
        }
        compose.waitForIdle()
    }

    private fun replaceBy(page: PageState) {
        state = state.copy(pages = mapOf(5 to page))
        compose.waitForIdle()
    }

    /** The colour in the middle of the page view. */
    private fun middle(): Color {
        val pixels = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    @Test
    fun `a page's picture is asked with no fade, though the loader fades by default`() {
        show(pageImage(folder.root, 5, Width, Height, android.graphics.Color.RED))

        assertThat(images.requests).isNotEmpty()
        images.requests.forEach { assertThat(it.transitionFactory).isSameInstanceAs(Transition.Factory.NONE) }
        assertThat(middle().red).isGreaterThan(0.8f)
    }

    @Test
    fun `the picture under a sharper one is the one that was shown, its file gone and never asked again`() {
        val red = pageImage(folder.root, 5, Width, Height, android.graphics.Color.RED)
        show(red)
        assertThat(middle().red).isGreaterThan(0.8f)

        // As the drawer does: the sharper picture is told, the file of the smaller one goes at once.
        red.file.delete()
        images.requests.clear()
        // A sharper picture that cannot be decoded yet: its file is not there.
        replaceBy(PageState.Ready(File(folder.root, "not-there"), Width * 2, Height * 2))

        assertThat(middle().red).isGreaterThan(0.8f)
        assertThat(middle().blue).isLessThan(0.2f)
        assertThat(images.requests.map { it.data }).doesNotContain(red.file)
        compose.onAllNodesWithContentDescription("Page 6").assertCountEquals(1)

        images.requests.clear()
        replaceBy(pageImage(folder.root, 5, Width * 2, Height * 2, android.graphics.Color.BLUE))

        assertThat(middle().blue).isGreaterThan(0.8f)
        assertThat(middle().red).isLessThan(0.2f)
        assertThat(images.requests.map { it.data }).doesNotContain(red.file)
        compose.onAllNodesWithContentDescription("Page 6").assertCountEquals(1)
    }
}
