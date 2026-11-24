package app.tfdn.lirelle.feature.account.switcher

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val Gap = 24
private const val Top = 40
private const val Margin = 16

/** Where the menu of profiles goes, in a window 1280 by 800 whose rail is 80 wide. */
class MenuPositionTest {

    private val position = MenuPosition(Gap, Top, Margin)
    private val window = IntSize(1280, 800)
    private val menu = IntSize(380, 340)

    private fun place(anchor: IntRect, direction: LayoutDirection = LayoutDirection.Ltr, size: IntSize = menu) =
        position.calculatePosition(anchor, window, direction, size)

    @Test
    fun `it opens upwards from the button's bottom, past the rail's end`() {
        // The button, 48 wide, centred in the rail, 16 above the window's bottom.
        val button = IntRect(left = 16, top = 736, right = 64, bottom = 784)

        assertThat(place(button)).isEqualTo(IntOffset(x = 64 + Gap, y = 784 - 340))
    }

    @Test
    fun `from right to left it goes to the left of a rail that is at the right`() {
        val button = IntRect(left = 1216, top = 736, right = 1264, bottom = 784)

        assertThat(place(button, LayoutDirection.Rtl)).isEqualTo(IntOffset(x = 1216 - Gap - 380, y = 784 - 340))
    }

    @Test
    fun `a menu taller than what is above the button starts under the system's bar`() {
        val button = IntRect(left = 16, top = 736, right = 64, bottom = 784)

        assertThat(place(button, size = IntSize(380, 780)).y).isEqualTo(Top)
    }

    @Test
    fun `it stays inside the window, sideways`() {
        val farRight = IntRect(left = 1100, top = 736, right = 1148, bottom = 784)
        val farLeft = IntRect(left = 100, top = 736, right = 148, bottom = 784)

        assertThat(place(farRight).x).isEqualTo(1280 - Margin - 380)
        assertThat(place(farLeft, LayoutDirection.Rtl).x).isEqualTo(Margin)
    }
}
