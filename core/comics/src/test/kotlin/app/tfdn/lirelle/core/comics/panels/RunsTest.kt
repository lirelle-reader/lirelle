package app.tfdn.lirelle.core.comics.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunsTest {

    private fun plane(width: Int, height: Int, set: (Int, Int) -> Boolean) =
        Plane(width, height, BooleanArray(width * height) { set(it % width, it / width) })

    /** The counts of [plane] along its rows, for what is looked for across a zone. */
    private fun rows(plane: Plane) = LineCounts(plane, rows = true)

    /** The counts of [plane] along its columns, for what is looked for down a zone. */
    private fun columns(plane: Plane) = LineCounts(plane, rows = false)

    @Test
    fun `a gutter across a zone is one run, strictly inside`() {
        // Rows 40 to 49 are gutter, in a zone 100 by 100.
        val gutters = plane(100, 100) { _, y -> y in 40..49 }
        val runs = gutterRuns(gutters, rows(gutters), Box(0, 0, 100, 100), across = true, wander = 0)
        assertThat(runs).containsExactly(40 to 50)
    }

    @Test
    fun `a gutter touching the zone's edge is no cut`() {
        val gutters = plane(100, 100) { _, y -> y < 10 }
        assertThat(gutterRuns(gutters, rows(gutters), Box(0, 0, 100, 100), across = true, wander = 0)).isEmpty()
    }

    @Test
    fun `a gutter blocked on a small span still cuts, on a large one it does not`() {
        val small = plane(100, 100) { x, y -> y in 40..49 && x !in 50..54 }
        assertThat(gutterRuns(small, rows(small), Box(0, 0, 100, 100), across = true, wander = 0)).hasSize(1)
        val large = plane(100, 100) { x, y -> y in 40..49 && x !in 50..58 }
        assertThat(gutterRuns(large, rows(large), Box(0, 0, 100, 100), across = true, wander = 0)).isEmpty()
    }

    @Test
    fun `a gutter thinner than the least thickness is no cut`() {
        val thin = plane(100, 100) { _, y -> y in 40..41 }
        assertThat(gutterRuns(thin, rows(thin), Box(0, 0, 100, 100), across = true, wander = 0)).isEmpty()
    }

    @Test
    fun `a widened gutter gives back its own place`() {
        // What the cut passes is the gutter widened by the wander on each side: rows 35 to 54 for rows 40 to 49.
        val widened = plane(100, 100) { _, y -> y in 35..54 }
        val runs = gutterRuns(widened, rows(widened), Box(0, 0, 100, 100), across = true, wander = 5)
        assertThat(runs).containsExactly(40 to 50)
    }

    @Test
    fun `gutters down a zone are found the same way`() {
        val gutters = plane(100, 100) { x, _ -> x in 30..39 }
        val runs = gutterRuns(gutters, columns(gutters), Box(0, 0, 100, 100), across = false, wander = 0)
        assertThat(runs).containsExactly(30 to 40)
    }

    @Test
    fun `a thin stroke far from the zone's edges is a shared line`() {
        // A page 1000 by 1000; a zone that is all of it; a stroke on rows 500 and 501.
        val strokes = plane(1000, 1000) { _, y -> y in 500..501 }
        val runs = lineRuns(rows(strokes), 1000, 1000, Box(0, 0, 1000, 1000), across = true)
        assertThat(runs).containsExactly(500 to 502)
    }

    @Test
    fun `a stroke near the zone's top is none, as the rule under a caption`() {
        val strokes = plane(1000, 1000) { _, y -> y in 60..61 }
        assertThat(lineRuns(rows(strokes), 1000, 1000, Box(0, 0, 1000, 1000), across = true)).isEmpty()
    }

    @Test
    fun `a thick band of strokes is none`() {
        val strokes = plane(1000, 1000) { _, y -> y in 500..530 }
        assertThat(lineRuns(rows(strokes), 1000, 1000, Box(0, 0, 1000, 1000), across = true)).isEmpty()
    }

    @Test
    fun `a dark line on a light ground is a stroke, flat ground is none`() {
        val grey = ByteArray(40 * 40) { (if (it / 40 == 20) 20 else 200).toByte() }
        val (across, along) = strokes(grey, 40, 40)
        assertThat(across[10, 20]).isTrue()
        assertThat(across[10, 10]).isFalse()
        assertThat(along[10, 10]).isFalse()
    }

    @Test
    fun `a light line on a dark ground is a stroke too`() {
        val grey = ByteArray(40 * 40) { (if (it % 40 == 20) 230 else 40).toByte() }
        val (_, along) = strokes(grey, 40, 40)
        assertThat(along[20, 10]).isTrue()
        assertThat(along[5, 10]).isFalse()
    }
}
