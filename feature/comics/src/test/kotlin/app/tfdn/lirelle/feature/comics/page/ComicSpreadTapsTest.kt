package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import com.google.common.truth.Truth.assertThat
import kotlin.math.ceil
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tap on the page view: the edges turn, the middle shows or hides the bars. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadTapsTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val navigation = RecordingNavigation()
    private var toggles = 0
    private var hides = 0

    @Test
    fun `the right edge goes forward, the left edge back, the middle shows the bars`() {
        show(album(spreadIndex = 5))

        compose.tapAt(0.9f)
        assertThat(navigation.calls).containsExactly("next")
        compose.tapAt(0.1f)
        assertThat(navigation.calls).containsExactly("next", "previous").inOrder()
        assertThat(toggles).isEqualTo(0)

        compose.tapAt(0.5f)
        assertThat(toggles).isEqualTo(1)
        assertThat(navigation.calls).hasSize(2)
    }

    @Test
    fun `the middle is half of the width`() {
        show(album(spreadIndex = 5))

        compose.tapAt(0.3f)
        compose.tapAt(0.7f)

        assertThat(toggles).isEqualTo(2)
        assertThat(navigation.calls).isEmpty()
    }

    @Test
    fun `right to left, the left edge goes forward and the right edge back`() {
        show(album(spreadIndex = 5, settings = ComicSettings(direction = Direction.RightToLeft)))

        compose.tapAt(0.1f)
        assertThat(navigation.calls).containsExactly("next")
        compose.tapAt(0.9f)
        assertThat(navigation.calls).containsExactly("next", "previous").inOrder()

        compose.tapAt(0.5f)
        assertThat(toggles).isEqualTo(1)
    }

    @Test
    fun `fitted to the width, the forward edge first moves down the page, screen by screen, then turns`() {
        // Five times as high as it is wide: far higher than the screen.
        val tall = pageImage(folder.root, index = 5, width = 100, height = 500)
        show(album(spreadIndex = 5, settings = ComicSettings(fit = Fit.Width), pages = mapOf(5 to tall)))
        val screen = compose.viewport()
        val page = compose.onNodeWithContentDescription("Page 6")
        assertThat(page.drawn().top).isWithin(1f).of(0f)
        assertThat(page.drawn().height).isWithin(2f).of(screen.width * 5)

        compose.tapAt(0.9f)

        assertThat(navigation.calls).isEmpty()
        assertThat(page.drawn().top).isWithin(1f).of(-0.9f * screen.height)

        val hidden = page.drawn().height - screen.height
        repeat(ceil(hidden / (0.9f * screen.height)).toInt() - 1) { compose.tapAt(0.9f) }
        assertThat(navigation.calls).isEmpty()
        assertThat(page.drawn().bottom).isWithin(1f).of(screen.height)

        compose.tapAt(0.9f)
        assertThat(navigation.calls).containsExactly("next")
    }

    @Test
    fun `zoomed on the top left, the forward edge first moves along the row and does not turn`() {
        show(album(spreadIndex = 5))
        val screen = compose.viewport()
        compose.longPressAt(Offset(1f, 1f))
        val page = compose.onNodeWithContentDescription("Page 6")
        assertThat(page.drawn().width).isWithin(1f).of(2 * screen.width)
        assertThat(page.drawn().left).isWithin(2f).of(0f)

        compose.tapAt(0.9f)

        assertThat(navigation.calls).isEmpty()
        // The page moved as a turn would have: the bars are asked to go, not to show.
        assertThat(hides).isEqualTo(1)
        assertThat(toggles).isEqualTo(0)
        assertThat(page.drawn().left).isWithin(2f).of(-0.9f * screen.width)
        assertThat(page.drawn().top).isWithin(2f).of(0f)

        // The end of the row, then the row below from its start.
        compose.tapAt(0.9f)
        assertThat(page.drawn().right).isWithin(1f).of(screen.width)
        compose.tapAt(0.9f)
        assertThat(page.drawn().left).isWithin(1f).of(0f)
        assertThat(page.drawn().top).isWithin(2f).of(-0.9f * screen.height)
        assertThat(navigation.calls).isEmpty()
    }

    @Test
    fun `fitted to the width, the back edge moves up the page, and turns at its top`() {
        val tall = pageImage(folder.root, index = 5, width = 100, height = 500)
        show(album(spreadIndex = 5, settings = ComicSettings(fit = Fit.Width), pages = mapOf(5 to tall)))
        val page = compose.onNodeWithContentDescription("Page 6")

        compose.tapAt(0.9f)
        assertThat(page.drawn().top).isLessThan(-1f)
        compose.tapAt(0.1f)

        assertThat(navigation.calls).isEmpty()
        assertThat(page.drawn().top).isWithin(1f).of(0f)

        compose.tapAt(0.1f)
        assertThat(navigation.calls).containsExactly("previous")
    }

    @Test
    fun `a page reached going back, fitted to the width, shows its bottom`() {
        val tall = pageImage(folder.root, index = 5, width = 100, height = 500)
        val settings = ComicSettings(fit = Fit.Width)
        show(album(spreadIndex = 5, settings = settings, forward = false, pages = mapOf(5 to tall)))

        val page = compose.onNodeWithContentDescription("Page 6")

        assertThat(page.drawn().bottom).isWithin(1f).of(compose.viewport().height)
    }

    private fun show(state: ComicReaderUiState) {
        compose.setContent {
            images.Provide {
                LirelleTheme {
                    ComicSpreadView(state, navigation, onToggleBars = { toggles++ }, onHideBars = { hides++ })
                }
            }
        }
    }
}
