package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.layout.Corner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where a spread beside the one shown is drawn: as ever, but for one a step's turn brings in at a stop. */
class SpreadBesideTest {

    private val viewport = Size(400f, 800f)
    private val content = Size(400f, 600f)
    private val zoom = SpreadZoom()
    private val pans = SpreadPans()
    private val shown = SpreadPan()
    private var turning = false
    private val beside = SpreadBeside(zoom, pans, shown) { turning }
    private val stop = StopFrame(StopPlace(2.5f, Offset(-80f, -120f)), Rect(0f, 50f, 400f, 750f))

    @Test
    fun `a spread beside is drawn at the reader's zoom, at the corner it opens at`() {
        zoom.scale = 2f

        assertThat(beside.framed(6)).isNull()
        assertThat(beside.scale(6)).isEqualTo(2f)
        assertThat(beside.offset(6, content, viewport, Corner.TopLeft))
            .isEqualTo(panBounds(content, viewport, 2f).at(Corner.TopLeft))
        assertThat(beside.offset(4, content, viewport, Corner.BottomRight))
            .isEqualTo(panBounds(content, viewport, 2f).at(Corner.BottomRight))
    }

    @Test
    fun `a spread a tap moved ahead of a turn is drawn where the tap put it`() {
        zoom.scale = 2f
        pans.ahead(6).offset = Offset(-100f, -50f)
        turning = true

        assertThat(beside.framed(6)).isNull()
        assertThat(beside.scale(6)).isEqualTo(2f)
        assertThat(beside.offset(6, content, viewport, Corner.TopLeft)).isEqualTo(Offset(-100f, -50f))
    }

    @Test
    fun `while a turn leads to it, a spread framed ahead is drawn at its stop, whatever the reader's zoom`() {
        pans.ahead(6).framed = stop
        turning = true

        assertThat(beside.framed(6)).isEqualTo(stop)
        assertThat(beside.scale(6)).isEqualTo(2.5f)
        assertThat(beside.offset(6, content, viewport, Corner.TopLeft)).isEqualTo(Offset(-80f, -120f))
        // The spread at the other side is one like any other.
        assertThat(beside.framed(4)).isNull()
        assertThat(beside.scale(4)).isEqualTo(MinZoom)
    }

    @Test
    fun `with no turn on its way, a spread framed ahead is drawn whole at the corner it opens at`() {
        pans.ahead(6).framed = stop

        assertThat(beside.framed(6)).isNull()
        assertThat(beside.scale(6)).isEqualTo(MinZoom)
        assertThat(beside.offset(6, content, viewport, Corner.TopLeft))
            .isEqualTo(panBounds(content, viewport, MinZoom).at(Corner.TopLeft))
    }

    @Test
    fun `beside a spread that a stop frames, the others are whole at the corner they open at`() {
        shown.framed = stop
        zoom.scale = 2.5f

        assertThat(beside.scale(4)).isEqualTo(MinZoom)
        assertThat(beside.offset(4, content, viewport, Corner.BottomRight))
            .isEqualTo(panBounds(content, viewport, MinZoom).at(Corner.BottomRight))
    }
}
