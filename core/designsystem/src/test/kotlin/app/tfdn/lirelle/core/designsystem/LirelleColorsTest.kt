package app.tfdn.lirelle.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Test

class LirelleColorsTest {

    /** Relative luminance, as WCAG 2 defines it. */
    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    /** Contrast ratio between two colours, as WCAG 2 defines it. */
    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun assertSelectedDestinationStandsOut(colors: ColorScheme) {
        // The selected destination is a secondaryContainer pill laid on the navigation bar
        // (surfaceContainer) or on the rail (surface): it must be told from both.
        assertThat(contrast(colors.secondaryContainer, colors.surfaceContainer)).isAtLeast(1.5)
        assertThat(contrast(colors.secondaryContainer, colors.surface)).isAtLeast(1.5)
        // Its icon is drawn on the pill.
        assertThat(contrast(colors.onSecondaryContainer, colors.secondaryContainer)).isAtLeast(7.0)
    }

    @Test
    fun `the selected destination stands out in the light theme`() {
        assertSelectedDestinationStandsOut(LirelleLightColors)
    }

    @Test
    fun `the selected destination stands out in the dark theme`() {
        assertSelectedDestinationStandsOut(LirelleDarkColors)
    }

    @Test
    fun `the contrast ratio is the one of WCAG`() {
        assertThat(contrast(Color.Black, Color.White)).isWithin(0.001).of(21.0)
        assertThat(contrast(Color.White, Color.White)).isWithin(0.001).of(1.0)
    }
}
