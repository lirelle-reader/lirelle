package app.tfdn.lirelle.feature.book

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The least contrast of a drawing with what is around it, for it to be seen well. */
private const val VisibleContrast = 3f

/**
 * The colours of the ring, in the light and in the dark theme: the progress is the accent colour, seen well on
 * the page and next to the rest of the ring, which is fainter; the filled and the outlined buttons follow the
 * same rule.
 */
@RunWith(RobolectricTestRunner::class)
class ActionRingColorsTest {

    @get:Rule
    val compose = createComposeRule()

    private val filled = ActionRingUi(ActionKind.Read, enabled = true, RingStatus.ResumeAt(62), 0.62f, filled = true)
    private val outlined = filled.copy(kind = ActionKind.Listen, filled = false)

    private fun scheme(dark: Boolean): ColorScheme {
        lateinit var colours: ColorScheme
        compose.setContent { LirelleTheme(darkTheme = dark) { colours = MaterialTheme.colorScheme } }
        compose.waitForIdle()
        return colours
    }

    @Test
    fun `in the light theme, the progress is seen well and the track is fainter`() = assertRingsReadWell(dark = false)

    @Test
    fun `in the dark theme, the progress is seen well and the track is fainter`() = assertRingsReadWell(dark = true)

    @Test
    fun `the filled button is a disc of the accent, its track the accent faded`() {
        val scheme = scheme(dark = false)

        val colours = discColors(filled, scheme)

        assertThat(colours.disc).isEqualTo(scheme.primary)
        assertThat(colours.icon).isEqualTo(scheme.onPrimary)
        assertThat(colours.arc).isEqualTo(scheme.primary)
        assertThat(colours.track).isEqualTo(scheme.primary.copy(alpha = FilledTrackAlpha))
    }

    @Test
    fun `the outlined button is a quiet disc, its track a quiet line`() {
        val scheme = scheme(dark = false)

        val colours = discColors(outlined, scheme)

        assertThat(colours.disc).isEqualTo(scheme.surfaceContainerHigh)
        assertThat(colours.icon).isEqualTo(scheme.onSurface)
        assertThat(colours.arc).isEqualTo(scheme.primary)
        assertThat(colours.track).isEqualTo(scheme.outlineVariant)
    }

    @Test
    fun `a button that waits has the outlined look, dimmed`() {
        val scheme = scheme(dark = false)

        val colours = discColors(outlined.copy(enabled = false), scheme)

        assertThat(colours.dashed).isFalse()
        assertThat(colours.disc).isEqualTo(scheme.surfaceContainerHigh.copy(alpha = RingDisabledAlpha))
        assertThat(colours.arc).isEqualTo(scheme.primary.copy(alpha = RingDisabledAlpha))
    }

    @Test
    fun `a button whose format the device does not have is a dashed circle with no disc`() {
        val scheme = scheme(dark = false)

        val colours = discColors(outlined.copy(enabled = false, status = RingStatus.Offline), scheme)

        assertThat(colours.dashed).isTrue()
        assertThat(colours.disc).isEqualTo(Color.Transparent)
        assertThat(colours.track).isEqualTo(scheme.outlineVariant)
    }

    private fun assertRingsReadWell(dark: Boolean) {
        val scheme = scheme(dark)
        val page = scheme.background
        mapOf("filled" to filled, "outlined" to outlined).forEach { (what, ui) ->
            val colours = discColors(ui, scheme)
            // The ring is drawn on the page, apart from the disc: its colours are seen over the page's.
            val arc = colours.arc.compositeOver(page)
            val track = colours.track.compositeOver(page)
            assertWithMessage("the $what arc on the page").that(contrast(arc, page)).isAtLeast(VisibleContrast)
            assertWithMessage("the $what arc next to its track").that(contrast(arc, track)).isAtLeast(VisibleContrast)
            assertWithMessage("the $what track on the page").that(contrast(track, page)).isLessThan(contrast(arc, page))
            assertWithMessage("the $what track is there").that(contrast(track, page)).isGreaterThan(1.2f)
            assertWithMessage("the $what icon on its disc").that(contrast(colours.icon, colours.disc))
                .isAtLeast(VisibleContrast)
        }
    }

    private fun contrast(one: Color, other: Color): Float {
        val lighter = maxOf(one.luminance(), other.luminance())
        val darker = minOf(one.luminance(), other.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
