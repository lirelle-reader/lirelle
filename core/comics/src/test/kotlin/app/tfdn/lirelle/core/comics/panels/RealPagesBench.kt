package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.settings.Direction
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

private const val Near = 0.001f
private const val Shown = 20
private const val NanosInMilli = 1_000_000

/**
 * The cut on real pages kept outside the repository, against the reference's answers on the very same pictures:
 * `-Dpanels.pages=<folder>` holding PNG pages and an `expected.txt` as the test pages have. Says how many pages
 * are cut the same, how long a page takes, and which page takes longest. Skipped without the folder.
 */
class RealPagesBench {

    @Test
    fun `real pages are cut as the reference cuts them`() {
        val folder = System.getProperty("panels.pages")?.let(::File)
        assumeTrue(folder != null && folder.isDirectory)
        val lines = File(folder, "expected.txt").readLines().filter { it.isNotBlank() }
        var nanos = 0L
        var slowest = 0L
        var slowestName = ""
        val different = mutableListOf<String>()
        for (line in lines) {
            val fields = line.split(';', limit = 3)
            val picture = pictureOf(File(folder, "${fields[0]}.png"))
            val direction = if (fields[1] == "rtl") Direction.RightToLeft else Direction.LeftToRight
            val start = System.nanoTime()
            val got = cutPage(picture, direction)
            val took = System.nanoTime() - start
            nanos += took
            if (took > slowest) {
                slowest = took
                slowestName = fields[0]
            }
            val want = fields[2].split(' ').filter { it.isNotBlank() }
            if (!same(got, want)) {
                different += "${fields[0]} ${fields[1]}: ${got.size} zones, the reference ${want.size}"
            }
        }
        val millis = nanos / NanosInMilli / lines.size.coerceAtLeast(1)
        println("same as the reference: ${lines.size - different.size} of ${lines.size}; $millis ms a page")
        println("the slowest page: $slowestName, ${slowest / NanosInMilli} ms")
        different.take(Shown).forEach(::println)
        check(different.isEmpty()) { "${different.size} pages are cut otherwise than by the reference" }
    }

    private fun pictureOf(file: File): PagePixels {
        val image = ImageIO.read(file)
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return PagePixels(image.width, image.height, argb)
    }

    private fun same(got: List<Zone>, want: List<String>): Boolean =
        got.size == want.size && got.zip(want).all { (zone, text) ->
            val fields = text.split(',')
            val edges = listOf(zone.left, zone.top, zone.right, zone.bottom)
            edges.indices.all { abs(edges[it] - fields[it].toFloat()) < Near } &&
                (zone.kind == ZoneKind.Filled) == (fields[edges.size] == "P")
        }
}
