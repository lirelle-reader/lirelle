package app.tfdn.lirelle.feature.reader.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.feature.reader.FooterPreviewTag
import app.tfdn.lirelle.feature.reader.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The Reading page on one column: where it is tall enough, the preview stays in place at the top, on two
 * fifths of the height at most, and only the controls scroll under it. Where it is not, all scrolls together.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingSettingsFixedPreviewTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w411dp-h900dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The largest text there is: the preview is as tall as it gets. */
    private val largest = ReaderSettings(fontSizeStep = FontSizesPx.lastIndex, lineHeightStep = LineHeights.lastIndex)

    /**
     * @param height the height of the window, the whole of the device's when null.
     * @param fontScale the system's text size.
     */
    private fun show(settings: ReaderSettings = ReaderSettings(), height: Dp? = null, fontScale: Float = 1f) {
        val host = PageHost(settings)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                LirelleTheme {
                    Box(if (height == null) Modifier else Modifier.fillMaxWidth().height(height)) {
                        ReadingSettingsPage(host.settings, host.change, onBack = {})
                    }
                }
            }
        }
    }

    private fun Dp.px(): Float = with(compose.density) { toPx() }

    private fun lastControl(): SemanticsNodeInteraction =
        compose.onNode(hasText(string(R.string.reader_more_corner_bookmark)) and isToggleable())

    private fun approximate(): SemanticsNodeInteraction =
        compose.onNodeWithText(string(R.string.reader_preview_approximate))

    private fun previewInsideTheControls(): SemanticsNodeInteraction =
        compose.onNode(hasTestTag(ReadingPreviewTag) and hasAnyAncestor(hasTestTag(ReadingControlsTag)))

    @Test
    fun `the preview stays in place when the controls are scrolled to their last one`() {
        show()
        val before = compose.preview().bounds()
        val heading = compose.onNode(hasText(string(R.string.reader_settings_text)) and isHeading())
        heading.assertIsDisplayed()

        lastControl().performScrollTo()

        lastControl().assertIsDisplayed()
        heading.assertIsNotDisplayed()
        compose.preview().assertIsDisplayed().assertIsWhollyShown()
        approximate().assertIsDisplayed()
        assertThat(compose.preview().bounds()).isEqualTo(before)
    }

    @Test
    fun `the controls scroll on their own, under the preview`() {
        show()

        val controls = compose.onNodeWithTag(ReadingControlsTag).bounds()

        previewInsideTheControls().assertDoesNotExist()
        assertThat(controls.top).isAtLeast(approximate().bounds().bottom)
        // The controls keep the preview's width: the gutters aside, one column of 600 dp at most.
        assertThat(controls.left).isWithin(1f).of(compose.preview().bounds().left - 16.dp.px())
        assertThat(controls.right).isWithin(1f).of(compose.preview().bounds().right + 16.dp.px())
    }

    @Test
    fun `a preview of very large text takes two fifths of the height at most and scrolls inside itself`() {
        show(largest, fontScale = 2f)
        val preview = compose.preview().bounds()
        val controls = compose.onNodeWithTag(ReadingControlsTag).bounds()
        // The page's content starts 8 dp above the preview and ends where the controls end.
        val top = preview.top - 8.dp.px()
        val content = controls.bottom - top

        assertThat(preview.height).isAtMost(content * 0.4f)
        assertThat(approximate().bounds().bottom - top).isAtMost(content * 0.4f + 1f)
        approximate().assertIsDisplayed()
        // The controls have the rest, but for the line between the two.
        assertThat(controls.height).isAtLeast(content * 0.6f - 2.dp.px())

        val footer = compose.onNodeWithTag(FooterPreviewTag)
        footer.assertIsNotDisplayed()
        footer.performScrollTo()
        footer.assertIsDisplayed()
        // The card scrolled inside itself: it has not moved, nor grown.
        assertThat(compose.preview().bounds()).isEqualTo(preview)
    }

    @Test
    fun `a preview that fits is shown whole and does not take more room than it needs`() {
        show()

        compose.onNodeWithTag(FooterPreviewTag).assertIsDisplayed()
        val gap = compose.onNodeWithTag(ReadingControlsTag).bounds().top - approximate().bounds().bottom
        assertThat(gap).isAtMost(16.dp.px())
    }

    @Test
    fun `on a page less than 480 dp tall, the preview scrolls away with the rest`() {
        show(height = 400.dp)
        compose.preview().assertIsDisplayed()

        lastControl().performScrollTo()

        lastControl().assertIsDisplayed()
        compose.preview().assertIsNotDisplayed()
    }

    @Test
    fun `on a page just tall enough, the preview stays in place`() {
        // The bar takes 64 dp of the window: 480 dp are left to the content.
        show(height = 544.dp)

        lastControl().performScrollTo()

        compose.preview().assertIsDisplayed()
    }

    @Test
    fun `on a page just too short, the preview scrolls away`() {
        show(height = 543.dp)

        lastControl().performScrollTo()

        compose.preview().assertIsNotDisplayed()
    }
}
