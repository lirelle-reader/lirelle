package app.tfdn.lirelle.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpeedShortcutsTest {

    @Test
    fun `the next speed is the shortcut after the current one, the first after the last`() {
        assertThat(nextSpeedShortcut(1f)).isEqualTo(1.25f)
        assertThat(nextSpeedShortcut(1.25f)).isEqualTo(1.5f)
        assertThat(nextSpeedShortcut(1.5f)).isEqualTo(2f)
        assertThat(nextSpeedShortcut(2f)).isEqualTo(1f)
    }

    @Test
    fun `a speed that is no shortcut goes to the next higher one, to the first above the last`() {
        assertThat(nextSpeedShortcut(0.8f)).isEqualTo(1f)
        assertThat(nextSpeedShortcut(1.1f)).isEqualTo(1.25f)
        assertThat(nextSpeedShortcut(3f)).isEqualTo(1f)
    }
}
