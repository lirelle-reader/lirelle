package app.tfdn.lirelle.core.comics.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaneTest {

    /** A plane from rows of '#' (true) and '.' (false). */
    private fun plane(vararg rows: String): Plane {
        val width = rows[0].length
        return Plane(width, rows.size, BooleanArray(rows.size * width) { rows[it / width][it % width] == '#' })
    }

    private fun Plane.rows(): List<String> =
        (0 until height).map { y -> (0 until width).joinToString("") { x -> if (this[x, y]) "#" else "." } }

    @Test
    fun `eroding keeps what has its whole box set, the outside counting as set`() {
        val eroded = plane("#####", "#####", "##.##", "#####").boxed(3, 3, erode = true)
        assertThat(eroded.rows()).containsExactly("#####", "#...#", "#...#", "#...#").inOrder()
    }

    @Test
    fun `dilating sets what has anything set in its box, the outside counting as unset`() {
        val dilated = plane(".....", "..#..", ".....", ".....").boxed(3, 1, erode = false)
        assertThat(dilated.rows()).containsExactly(".....", ".###.", ".....", ".....").inOrder()
    }

    @Test
    fun `a box may be wider one way than the other`() {
        val dilated = plane(".....", "..#..", ".....", ".....", ".....").boxed(1, 3, erode = false)
        assertThat(dilated.rows()).containsExactly("..#..", "..#..", "..#..", ".....", ".....").inOrder()
    }

    @Test
    fun `a box of one cell one way leaves that way alone`() {
        val page = plane("#..#.", ".....", "..#..")
        assertThat(page.boxed(1, 1, erode = false).rows()).containsExactly("#..#.", ".....", "..#..").inOrder()
        assertThat(page.boxed(3, 1, erode = false).rows()).containsExactly("#####", ".....", ".###.").inOrder()
        assertThat(page.boxed(1, 3, erode = true).rows()).containsExactly(".....", ".....", ".....").inOrder()
        // The plane it was made from is as it was.
        assertThat(page.rows()).containsExactly("#..#.", ".....", "..#..").inOrder()
    }

    @Test
    fun `a box taller than the plane takes whole columns`() {
        val page = plane("#.#.", "..#.", "..##")
        assertThat(page.boxed(1, 9, erode = false).rows()).containsExactly("#.##", "#.##", "#.##").inOrder()
        assertThat(page.boxed(1, 9, erode = true).rows()).containsExactly("..#.", "..#.", "..#.").inOrder()
    }

    @Test
    fun `a box wider than the plane takes whole rows`() {
        val page = plane("#...", "####", "..#.")
        assertThat(page.boxed(11, 1, erode = false).rows()).containsExactly("####", "####", "####").inOrder()
        assertThat(page.boxed(11, 1, erode = true).rows()).containsExactly("....", "####", "....").inOrder()
    }

    @Test
    fun `a box larger than the plane both ways takes it whole`() {
        assertThat(plane("#...", "....", "....").boxed(9, 9, erode = false).rows())
            .containsExactly("####", "####", "####").inOrder()
        assertThat(plane("####", "##.#", "####").boxed(9, 9, erode = true).rows())
            .containsExactly("....", "....", "....").inOrder()
        assertThat(plane("####", "####").boxed(9, 9, erode = true).rows()).containsExactly("####", "####").inOrder()
    }

    @Test
    fun `eroding and dilating by a box of five are told from one of three`() {
        val page = plane(".......", ".#####.", ".#####.", ".#####.", ".#####.", ".#####.", ".......")
        assertThat(page.boxed(5, 5, erode = true).rows())
            .containsExactly(".......", ".......", ".......", "...#...", ".......", ".......", ".......").inOrder()
        assertThat(page.boxed(5, 3, erode = true).rows())
            .containsExactly(".......", ".......", "...#...", "...#...", "...#...", ".......", ".......").inOrder()
        val dot = plane(".......", ".......", ".......", "...#...", ".......", ".......", ".......")
        assertThat(dot.boxed(3, 5, erode = false).rows())
            .containsExactly(".......", "..###..", "..###..", "..###..", "..###..", "..###..", ".......").inOrder()
    }

    @Test
    fun `line counts count what is set on a stretch of a row, or of a column`() {
        val page = plane("##.#", "#...", "#..#")
        val rows = LineCounts(page, rows = true)
        assertThat(rows.onRow(0, 0, 4)).isEqualTo(3)
        assertThat(rows.onRow(0, 1, 3)).isEqualTo(1)
        assertThat(rows.onRow(0, 2, 2)).isEqualTo(0)
        assertThat(rows.onRow(1, 1, 4)).isEqualTo(0)
        assertThat(rows.onRow(2, 3, 4)).isEqualTo(1)
        val columns = LineCounts(page, rows = false)
        assertThat(columns.onColumn(0, 0, 3)).isEqualTo(3)
        assertThat(columns.onColumn(0, 1, 2)).isEqualTo(1)
        assertThat(columns.onColumn(1, 1, 3)).isEqualTo(0)
        assertThat(columns.onColumn(3, 0, 3)).isEqualTo(2)
        assertThat(columns.onColumn(3, 1, 2)).isEqualTo(0)
    }

    @Test
    fun `line counts agree with the sums on every stretch of every line`() {
        val random = java.util.Random(7)
        val page = Plane(23, 17, BooleanArray(23 * 17) { random.nextInt(3) > 0 })
        val sums = Sums(page)
        val rows = LineCounts(page, rows = true)
        val columns = LineCounts(page, rows = false)
        for (y in 0 until 17) {
            for (from in 0..23) for (to in from..23) {
                assertThat(rows.onRow(y, from, to)).isEqualTo(sums.count(from, y, to, y + 1))
            }
        }
        for (x in 0 until 23) {
            for (from in 0..17) for (to in from..17) {
                assertThat(columns.onColumn(x, from, to)).isEqualTo(sums.count(x, from, x + 1, to))
            }
        }
    }

    @Test
    fun `counts made along rows are not read as columns, nor the other way`() {
        val page = plane("##.#", "#...", "#..#")
        val asColumn = runCatching { LineCounts(page, rows = true).onColumn(0, 0, 3) }
        val asRow = runCatching { LineCounts(page, rows = false).onRow(0, 0, 4) }
        assertThat(asColumn.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(asRow.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sums count what is set in a rectangle`() {
        val sums = Sums(plane("##..", "#...", "...#"))
        assertThat(sums.count(0, 0, 4, 3)).isEqualTo(4)
        assertThat(sums.count(0, 0, 2, 2)).isEqualTo(3)
        assertThat(sums.count(2, 0, 4, 2)).isEqualTo(0)
        assertThat(sums.count(3, 2, 4, 3)).isEqualTo(1)
    }

    @Test
    fun `flooding from a corner takes what touches it by a side, not by a corner`() {
        val flooded = plane("##.#", "#..#", ".#.#").floodedFromCorner()
        assertThat(flooded.rows()).containsExactly("##..", "#...", "....").inOrder()
    }

    @Test
    fun `blobs are told with their boxes, corners touching being one blob`() {
        val blobs = plane("#..#", ".#..", "...#").takeBlobs()
        assertThat(blobs.map { it.box }).containsExactly(Box(0, 0, 2, 2), Box(3, 0, 4, 1), Box(3, 2, 4, 3))
    }

    @Test
    fun `a blob is told with its own cells, not those of another one in its box`() {
        // An L, and a dot inside its box that touches it by no side and no corner.
        val page = plane("#...", "#.#.", "#...", "####")
        val blobs = page.takeBlobs()
        assertThat(blobs.map { it.box }).containsExactly(Box(0, 0, 4, 4), Box(2, 1, 3, 2)).inOrder()
        assertThat(cellsOf(blobs[0])).containsExactly(0, 4, 8, 12, 13, 14, 15)
        assertThat(cellsOf(blobs[1])).containsExactly(6)
    }

    @Test
    fun `taking the blobs of a plane empties it`() {
        val page = plane("#..#", ".#..", "...#")
        page.takeBlobs()
        assertThat(page.rows()).containsExactly("....", "....", "....").inOrder()
    }

    /** The cells of [blob], each as its place in the plane. */
    private fun cellsOf(blob: Blob): List<Int> {
        val cells = mutableListOf<Int>()
        blob.forEachCell { cells += it }
        return cells
    }
}
