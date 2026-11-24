package app.tfdn.lirelle.core.comics.layout

import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectionsTest {

    @Test
    fun `left to right draws the first page of a pair on the left`() {
        assertThat(Spread(3, 4).drawn(Direction.LeftToRight)).containsExactly(3, 4).inOrder()
    }

    @Test
    fun `right to left draws the first page of a pair on the right`() {
        assertThat(Spread(3, 4).drawn(Direction.RightToLeft)).containsExactly(4, 3).inOrder()
    }

    @Test
    fun `a page alone is drawn alone in both directions`() {
        assertThat(Spread(3).drawn(Direction.LeftToRight)).containsExactly(3)
        assertThat(Spread(3).drawn(Direction.RightToLeft)).containsExactly(3)
    }

    @Test
    fun `the edge at the end of the reading direction goes forward`() {
        assertThat(Direction.LeftToRight.forwardEdge()).isEqualTo(Edge.Right)
        assertThat(Direction.RightToLeft.forwardEdge()).isEqualTo(Edge.Left)
    }

    @Test
    fun `a page reached going forward opens at its top, where reading starts`() {
        assertThat(Direction.LeftToRight.openingCorner(forward = true)).isEqualTo(Corner.TopLeft)
        assertThat(Direction.RightToLeft.openingCorner(forward = true)).isEqualTo(Corner.TopRight)
    }

    @Test
    fun `a page reached going back opens at its bottom, where reading ends`() {
        assertThat(Direction.LeftToRight.openingCorner(forward = false)).isEqualTo(Corner.BottomRight)
        assertThat(Direction.RightToLeft.openingCorner(forward = false)).isEqualTo(Corner.BottomLeft)
    }

    @Test
    fun `a corner says its sides`() {
        assertThat(Corner.entries.filter { it.top }).containsExactly(Corner.TopLeft, Corner.TopRight)
        assertThat(Corner.entries.filter { it.left }).containsExactly(Corner.TopLeft, Corner.BottomLeft)
    }
}
