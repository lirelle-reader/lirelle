package app.tfdn.lirelle.feature.player.bookmarks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** How the chapters and bookmarks tabs share the width. */
class TabWidthsTest {

    @Test
    fun `two labels that fit in half take half each`() {
        assertThat(tabWidths(400, listOf(120, 150))).containsExactly(200, 200).inOrder()
    }

    @Test
    fun `a label wider than half gets its width, the other the rest`() {
        assertThat(tabWidths(400, listOf(140, 240))).containsExactly(160, 240).inOrder()
        assertThat(tabWidths(400, listOf(240, 140))).containsExactly(240, 160).inOrder()
    }

    @Test
    fun `labels wider together than the row each get their own, and the row scrolls`() {
        assertThat(tabWidths(320, listOf(150, 230))).containsExactly(150, 230).inOrder()
    }
}
