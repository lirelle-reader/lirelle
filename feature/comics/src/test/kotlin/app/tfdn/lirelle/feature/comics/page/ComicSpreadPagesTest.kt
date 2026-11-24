package app.tfdn.lirelle.feature.comics.page

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.R as UiR
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the page view draws: the pages of the spread in their order, a page that loads, one that failed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPagesTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val navigation = RecordingNavigation()

    @Test
    fun `a page that failed says which, why, and asks again for that page`() {
        show(album(spreadIndex = 12, pages = mapOf(12 to PageState.Failed(ApiError.ServerUnreachable))))

        compose.onNodeWithText("Page 13 could not be loaded").assertIsDisplayed()
        compose.onNodeWithText(context.getString(UiR.string.library_error_unreachable)).assertIsDisplayed()
        val retry = compose.onNodeWithText("Try again").assertIsDisplayed().assertHasClickAction()
        assertThat(compose.onNodeWithTag(ComicPageRetryTag).drawn().height).isAtLeast(48f)

        retry.performClick()

        assertThat(navigation.calls).containsExactly("retry 12")
    }

    @Test
    fun `a page that loads shows a mark and has its name`() {
        show(album(spreadIndex = 4))

        compose.onNodeWithTag(ComicPageLoadingTag).assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 5").assertIsDisplayed()
    }

    @Test
    fun `a page on the device is an image named by its number, without a mark`() {
        show(album(spreadIndex = 11, pages = mapOf(11 to pageImage(folder.root, 11, width = 165, height = 240))))

        compose.onNodeWithContentDescription("Page 12").assertIsDisplayed()
        compose.onAllNodesWithTag(ComicPageLoadingTag).assertCountEquals(0)
    }

    @Test
    fun `a page on the device is drawn by the time the screen is idle`() {
        val red = pageImage(folder.root, 11, width = 165, height = 240, colour = android.graphics.Color.RED)
        show(album(spreadIndex = 11, settings = ComicSettings(background = Background.White), pages = mapOf(11 to red)))

        val pixels = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()
        val middle = pixels[pixels.width / 2, pixels.height / 2]

        // The picture was read and decoded within the test: nothing of it is left to another thread.
        assertThat(middle.red).isGreaterThan(0.8f)
        assertThat(middle.green).isLessThan(0.2f)
    }

    @Test
    fun `a page offers a screen reader to turn it`() {
        show(album(spreadIndex = 11, pages = mapOf(11 to pageImage(folder.root, 11, width = 165, height = 240))))

        val actions = compose.onNodeWithContentDescription("Page 12").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()

        assertThat(actions.map { it.label }).containsExactly("Next page", "Previous page").inOrder()
        compose.runOnIdle { actions.forEach { it.action() } }
        assertThat(navigation.calls).containsExactly("next", "previous").inOrder()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `a page that loads and one that failed offer it too`() {
        val failed = PageState.Failed(ApiError.ServerFailed)
        show(album(spreadIndex = 2, twoPages = true, pages = mapOf(4 to failed)))

        val loading = compose.onNodeWithContentDescription("Page 4").fetchSemanticsNode()
        val message = compose.onNodeWithText("Page 5 could not be loaded").fetchSemanticsNode()

        listOf(loading, message).forEach { node ->
            assertThat(node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label })
                .containsExactly("Next page", "Previous page").inOrder()
        }
    }

    @Test
    fun `a page alone is in the middle of the screen`() {
        // Wider than high: the screen's width stops it, and it is lower than the screen.
        show(album(spreadIndex = 3, pages = mapOf(3 to pageImage(folder.root, 3, width = 300, height = 200))))

        val screen = compose.viewport()
        val page = compose.onNodeWithContentDescription("Page 4").drawn()

        assertThat(page.left).isWithin(1f).of(0f)
        assertThat(page.right).isWithin(1f).of(screen.width)
        assertThat(page.top).isWithin(1f).of(screen.height - page.bottom)
        assertThat(page.width / page.height).isWithin(0.02f).of(1.5f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `two pages are side by side in reading order, without a gap, in the middle`() {
        show(pair(Direction.LeftToRight))

        val screen = compose.viewport()
        val first = compose.onNodeWithContentDescription("Page 4").drawn()
        val second = compose.onNodeWithContentDescription("Page 5").drawn()

        assertThat(first.right).isWithin(1f).of(second.left)
        assertThat(first.left).isLessThan(second.left)
        assertThat(first.left).isWithin(1.5f).of(screen.width - second.right)
        assertThat(first.height).isWithin(1f).of(screen.height)
        assertThat(first.width).isWithin(1f).of(second.width)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `in two pages a page that stands alone is whole, even fitted to the width`() {
        val cover = pageImage(folder.root, 0, width = 165, height = 240)
        val fitted = ComicSettings(fit = Fit.Width)
        show(album(spreadIndex = 0, twoPages = true, settings = fitted, pages = mapOf(0 to cover)))

        val screen = compose.viewport()
        val page = compose.onNodeWithContentDescription("Page 1").drawn()

        assertThat(page.height).isWithin(1f).of(screen.height)
        assertThat(page.width).isLessThan(screen.width)
        assertThat(page.left).isWithin(1.5f).of(screen.width - page.right)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `in one page a page fitted to the width fills it`() {
        val cover = pageImage(folder.root, 0, width = 165, height = 240)
        show(album(spreadIndex = 0, settings = ComicSettings(fit = Fit.Width), pages = mapOf(0 to cover)))

        val screen = compose.viewport()
        val page = compose.onNodeWithContentDescription("Page 1").drawn()

        assertThat(page.width).isWithin(1f).of(screen.width)
        assertThat(page.height).isGreaterThan(screen.height)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `right to left, the first of two pages is at the right`() {
        show(pair(Direction.RightToLeft))

        val first = compose.onNodeWithContentDescription("Page 4").drawn()
        val second = compose.onNodeWithContentDescription("Page 5").drawn()

        assertThat(second.right).isWithin(1f).of(first.left)
        assertThat(second.left).isLessThan(first.left)
    }

    @Test
    fun `the background is black, grey or white, and what is written on it reads well`() {
        var background by mutableStateOf(Background.Black)
        compose.setContent {
            LirelleTheme { ComicSpreadView(album(settings = ComicSettings(background = background)), navigation, {}) }
        }
        val seen = Background.entries.associateWith {
            background = it
            compose.waitForIdle()
            compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()[2, 2]
        }

        assertThat(seen[Background.Black]).isEqualTo(Color.Black)
        assertThat(seen[Background.White]).isEqualTo(Color.White)
        val grey = seen.getValue(Background.Grey)
        assertThat(grey.red).isWithin(0.01f).of(grey.blue)
        assertThat(grey.red).isWithin(0.01f).of(grey.green)
        assertThat(grey.red).isWithin(0.05f).of(0.5f)
        Background.entries.forEach {
            val colours = it.colours()
            val (dark, light) = listOf(colours.background.luminance(), colours.content.luminance()).sorted()
            assertThat((light + 0.05f) / (dark + 0.05f)).isAtLeast(4.5f)
        }
    }

    /** The pages 3 and 4 (from 0) side by side, both on the device. */
    private fun pair(direction: Direction) = album(
        spreadIndex = 2,
        twoPages = true,
        settings = ComicSettings(direction = direction),
        pages = mapOf(
            3 to pageImage(folder.root, 3, width = 165, height = 240),
            4 to pageImage(folder.root, 4, width = 165, height = 240),
        ),
    )

    private fun show(state: ComicReaderUiState) {
        compose.setContent {
            images.Provide {
                LirelleTheme { ComicSpreadView(state, navigation, onToggleBars = {}) }
            }
        }
    }
}
