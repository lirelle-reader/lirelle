package app.tfdn.lirelle.feature.comics.strip

import androidx.compose.ui.unit.IntSize
import app.tfdn.lirelle.core.comics.PageState
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** The strip laid out from what the reader has of its pages, in a window of 400 by 800 pixels. */
class StripFrameTest {
    private val window = IntSize(400, 800)
    private val pictures = StripPictures()

    private fun page(width: Int, height: Int) = PageState.Ready(File("page"), width, height)

    @Test
    fun `a page is as high as its shape gives, as wide as the strip`() {
        val frame = pictures.frame(mapOf(0 to page(800, 1000), 1 to page(200, 300)), pageCount = 2, window)

        assertThat(frame.rooms.map { it.height }).containsExactly(500, 600).inOrder()
        assertThat(frame.layout.size).isEqualTo(2)
    }

    @Test
    fun `a page not there takes the shape of the nearest one seen, a screen when none was`() {
        assertThat(pictures.frame(emptyMap(), pageCount = 2, window).rooms.map { it.height })
            .containsExactly(800, 800)

        val frame = pictures.frame(mapOf(2 to page(400, 500)), pageCount = 4, window)

        assertThat(frame.rooms.map { it.height }).containsExactly(500, 500, 500, 500)
    }

    @Test
    fun `a page the reader no longer tells keeps its height`() {
        pictures.frame(mapOf(0 to page(400, 700), 1 to page(400, 500)), pageCount = 2, window)

        val frame = pictures.frame(mapOf(1 to page(400, 500)), pageCount = 2, window)

        assertThat(frame.rooms.map { it.height }).containsExactly(700, 500).inOrder()
    }

    @Test
    fun `the slices of a tall page add up to its height, to the pixel`() {
        // Twice as wide as the strip: 15,001 rows are 7,500.5 on screen.
        val frame = pictures.frame(mapOf(1 to page(800, 15_001)), pageCount = 3, window)

        val room = frame.rooms[1]
        assertThat(room.items).isEqualTo(4)
        assertThat(room.sample).isEqualTo(2)
        assertThat((0 until room.items).sumOf { room.heightOf(it) }).isEqualTo(room.height)
        assertThat(room.heightOf(0)).isEqualTo(2048)
        assertThat(frame.layout.size).isEqualTo(6)
        assertThat(frame.layout.itemOf(2)).isEqualTo(5)
    }

    @Test
    fun `a tall page that cannot be read in parts is one item, as high`() {
        val pages = mapOf(0 to page(400, 6000))
        assertThat(pictures.frame(pages, pageCount = 1, window).rooms[0].items).isEqualTo(3)

        pictures.unreadable(0)
        val room = pictures.frame(pages, pageCount = 1, window).rooms[0]

        assertThat(room.items).isEqualTo(1)
        assertThat(room.heightOf(0)).isEqualTo(6000)
    }

    @Test
    fun `an item keeps its key when a page above it turns out to be several slices`() {
        val before = pictures.frame(mapOf(1 to page(400, 600)), pageCount = 2, window)
        val after = pictures.frame(mapOf(0 to page(400, 6000), 1 to page(400, 600)), pageCount = 2, window)

        assertThat(before.keyOf(1)).isEqualTo(after.keyOf(3))
        assertThat(pageOfStripKey(after.keyOf(3))).isEqualTo(1)
        assertThat(pageOfStripKey(after.keyOf(2))).isEqualTo(0)
        assertThat((0 until after.layout.size).map(after::keyOf).toSet()).hasSize(4)
    }
}
