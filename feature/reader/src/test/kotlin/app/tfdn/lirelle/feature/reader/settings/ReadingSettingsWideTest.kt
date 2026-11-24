package app.tfdn.lirelle.feature.reader.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.feature.reader.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The Reading page where it has room: the preview stays in place beside the controls, which scroll, and the
 * steppers and the header and footer rows go two by two. With less room it is the phone's page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingSettingsWideTest {

    // Not tall: the controls are taller than the pane and scroll.
    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w900dp-h600dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** @param width the width of the pane, the whole window when null. */
    private fun show(width: Dp? = null, inPane: Boolean = true) {
        val host = PageHost()
        compose.setContent {
            LirelleTheme {
                Box(if (width == null) Modifier else Modifier.width(width).fillMaxHeight()) {
                    ReadingSettingsPage(host.settings, host.change, onBack = if (inPane) null else ({}))
                }
            }
        }
    }

    private fun Dp.px(): Float = with(compose.density) { toPx() }

    private fun text(@StringRes id: Int): Rect = compose.onNodeWithText(string(id)).bounds()

    private fun button(@StringRes spoken: Int): Rect = compose.onNodeWithContentDescription(string(spoken)).bounds()

    private fun controls(): Rect = compose.onNodeWithTag(ReadingControlsTag).bounds()

    private val steppers = listOf(
        R.string.reader_settings_size_larger,
        R.string.reader_settings_line_spacing_looser,
        R.string.reader_settings_margins_wider,
        R.string.reader_settings_alignment_next,
    )

    private val ends = listOf(
        R.string.reader_running_header_left,
        R.string.reader_running_header_right,
        R.string.reader_running_footer_left,
        R.string.reader_running_footer_right,
    )

    /** Asserts that four things are laid two by two, in reading order. */
    private fun assertTwoByTwo(cells: List<Rect>) {
        assertThat(cells).hasSize(4)
        val (first, second) = cells
        val (third, fourth) = cells.drop(2)
        assertThat(second.top).isEqualTo(first.top)
        assertThat(second.left).isAtLeast(first.right)
        assertThat(third.top).isAtLeast(first.bottom)
        assertThat(third.left).isEqualTo(first.left)
        assertThat(fourth.top).isEqualTo(third.top)
        assertThat(fourth.left).isEqualTo(second.left)
    }

    /** Asserts that things are one under the other. */
    private fun assertStacked(cells: List<Rect>) {
        cells.zipWithNext().forEach { (above, below) ->
            assertThat(below.top).isAtLeast(above.bottom)
            assertThat(below.left).isEqualTo(above.left)
        }
    }

    @Test
    fun `with room, the preview is beside the controls, 320 dp wide`() {
        show()

        val preview = compose.preview().bounds()

        assertThat(preview.left).isAtLeast(controls().right)
        assertThat(preview.width).isWithin(1f).of(320.dp.px())
        assertThat(text(R.string.reader_preview_approximate).top).isAtLeast(preview.bottom)
        assertThat(text(R.string.reader_preview_approximate).left).isAtLeast(controls().right)
    }

    @Test
    fun `the preview stays in place when the controls are scrolled to their end`() {
        show()
        val before = compose.preview().bounds()
        val heading = compose.onNode(hasText(string(R.string.reader_settings_text)) and isHeading())
        heading.assertIsDisplayed()

        compose.onNode(hasText(string(R.string.reader_edge_off)) and isSelectable()).performScrollTo()

        heading.assertIsNotDisplayed()
        compose.preview().assertIsDisplayed().assertIsWhollyShown()
        assertThat(compose.preview().bounds()).isEqualTo(before)
    }

    @Test
    fun `with room, the four steppers are two by two`() {
        show()

        assertTwoByTwo(steppers.map(::button))
    }

    @Test
    fun `with room, the four ends of the header and the footer are two by two`() {
        show()

        assertTwoByTwo(ends.map(::text))
    }

    @Test
    fun `beside the preview, a column of controls too narrow for pairs has one control a line`() {
        // The least width with two columns: 400 dp of controls.
        show(width = 752.dp)

        assertThat(compose.preview().bounds().left).isAtLeast(controls().right)
        assertThat(controls().width).isWithin(1f).of(400.dp.px())
        assertStacked(steppers.map(::button))
        assertStacked(ends.map(::text))
    }

    @Test
    fun `without room for both, the preview is on top and everything is one a line`() {
        show(width = 751.dp)

        val preview = compose.preview().bounds()

        assertThat(preview.bottom).isAtMost(text(R.string.reader_settings_text).top)
        assertStacked(steppers.map(::button))
        assertStacked(ends.map(::text))
    }

    @Test
    fun `on one column in a pane, the content is 600 dp at most, at the pane's start`() {
        show(width = 740.dp)

        val preview = compose.preview().bounds()

        assertThat(preview.left).isWithin(1f).of(16.dp.px())
        assertThat(preview.right).isWithin(1f).of(584.dp.px())
    }

    @Test
    fun `on one column on its own, the content is 600 dp at most, centred`() {
        show(width = 740.dp, inPane = false)

        val preview = compose.preview().bounds()

        assertThat(preview.left).isWithin(1f).of(86.dp.px())
        assertThat(preview.right).isWithin(1f).of(654.dp.px())
    }

    @Test
    fun `on one column in a pane, the preview stays in place over the controls`() {
        show(width = 740.dp)
        val before = compose.preview().bounds()

        compose.onNode(hasText(string(R.string.reader_edge_off)) and isSelectable()).performScrollTo()

        compose.preview().assertIsDisplayed().assertIsWhollyShown()
        assertThat(compose.preview().bounds()).isEqualTo(before)
        assertThat(controls().top).isAtLeast(before.bottom)
        assertThat(controls().width).isWithin(1f).of(600.dp.px())
    }
}
