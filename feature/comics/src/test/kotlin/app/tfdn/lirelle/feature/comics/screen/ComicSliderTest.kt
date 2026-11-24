package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import app.tfdn.lirelle.core.comics.layout.spreads
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.feature.comics.ComicSliderTag
import app.tfdn.lirelle.feature.comics.ComicSliderThumbTag
import app.tfdn.lirelle.feature.comics.page.drawn
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The slider over the pages: it previews while dragged, goes on release, and runs in the reading direction. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class ComicSliderTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenUnderTest()
    private val calls get() = screen.actions.calls
    private val slider get() = compose.onNodeWithTag(ComicSliderTag)

    @Test
    fun `it stands on the page shown, among the pages of the album`() {
        compose.setContent { screen.Content() }

        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

        assertThat(range.current).isEqualTo(11f)
        assertThat(range.range).isEqualTo(0f..47f)
        // A page at a time for a screen reader.
        assertThat(range.steps).isEqualTo(46)
    }

    @Test
    fun `dragged, the thumb moves as the finger does between two pages, and stands on a page once let go`() {
        screen.state = shortAlbum()
        compose.setContent { screen.Content() }
        val resting = thumb()
        val width = slider.drawn().width
        slider.performTouchInput {
            down(Offset(width * 0.3f, height / 2f))
            moveBy(Offset(width * 0.1f, 0f))
        }
        compose.waitForIdle()
        val places = mutableListOf(thumb())

        // A page is a seventh of the slider: a slider that stops on pages would stay, or go three times as far.
        repeat(3) {
            slider.performTouchInput { moveBy(Offset(width * 0.05f, 0f)) }
            compose.waitForIdle()
            places += thumb()
        }

        places.zipWithNext().forEach { (from, to) -> assertThat(to - from).isWithin(1.5f).of(0.05f * width) }
        assertThat(calls.all { it.startsWith("preview ") }).isTrue()
        assertThat(calls).containsNoDuplicates()
        assertThat(calls.last()).isAnyOf("preview 3", "preview 4")
        val previews = calls.size
        val nearest = calls.last().removePrefix("preview ")

        slider.performTouchInput { up() }
        compose.waitForIdle()

        assertThat(calls.drop(previews)).containsExactly("goTo $nearest")
        // The reader of this test stays where it was: so does the thumb.
        assertThat(thumb()).isWithin(1f).of(resting)
    }

    @Test
    fun `an arrow key moves it by a page, in the reading direction`() {
        compose.setContent { screen.Content() }

        slider.performSemanticsAction(SemanticsActions.RequestFocus)
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        slider.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()

        assertThat(calls).containsExactly("preview 12", "goTo 12", "preview 10", "goTo 10").inOrder()
    }

    /** An album of 8 pages on its second page: a page is a seventh of the slider. */
    private fun shortAlbum() = openAlbum(spreadIndex = 1).let {
        it.copy(pageCount = 8, spreads = spreads(8, wide = emptySet(), twoPages = false))
    }

    /** The middle of the thumb, from the left of the slider. */
    private fun thumb(): Float =
        compose.onNodeWithTag(ComicSliderThumbTag, useUnmergedTree = true).drawn().center.x - slider.drawn().left

    @Test
    fun `dragged, it previews each page it passes, and goes to the last one when let go`() {
        compose.setContent { screen.Content() }

        slider.performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            moveBy(Offset(width * 0.25f, 0f))
            moveBy(Offset(width * 0.25f, 0f))
        }
        compose.waitForIdle()

        assertThat(calls).isNotEmpty()
        assertThat(calls.all { it.startsWith("preview ") }).isTrue()
        assertThat(calls).containsNoDuplicates()
        val last = calls.last().removePrefix("preview ").toInt()
        // Three quarters of the way along 48 pages.
        assertThat(last).isIn(33..37)
        val previews = calls.size

        slider.performTouchInput { up() }
        compose.waitForIdle()

        assertThat(calls).hasSize(previews + 1)
        assertThat(calls.last()).isEqualTo("goTo $last")
    }

    @Test
    fun `a tap on it goes there`() {
        compose.setContent { screen.Content() }

        slider.performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        compose.waitForIdle()

        val target = calls.last().removePrefix("goTo ").toInt()
        assertThat(target).isIn(33..37)
        assertThat(calls).containsExactly("preview $target", "goTo $target").inOrder()
    }

    @Test
    fun `right to left, it runs from the right`() {
        screen.state = openAlbum(settings = ComicSettings(direction = Direction.RightToLeft))
        compose.setContent { screen.Content() }

        slider.performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        compose.waitForIdle()

        val target = calls.last().removePrefix("goTo ").toInt()
        assertThat(target).isIn(10..14)
        assertThat(calls).containsExactly("preview $target", "goTo $target").inOrder()
    }

    @Test
    fun `a screen reader sets the page, and the reader goes there`() {
        compose.setContent { screen.Content() }

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        compose.waitForIdle()

        assertThat(calls).containsExactly("preview 20", "goTo 20").inOrder()
    }

    @Test
    fun `it follows the page the reader previews`() {
        screen.state = openAlbum().copy(previewPage = 30)
        compose.setContent { screen.Content() }

        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]

        assertThat(range.current).isEqualTo(30f)
    }
}
