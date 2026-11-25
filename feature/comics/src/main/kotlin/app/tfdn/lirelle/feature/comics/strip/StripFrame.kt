package app.tfdn.lirelle.feature.comics.strip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import app.tfdn.lirelle.core.comics.PageState
import kotlin.math.roundToInt

/** The highest an item of the strip is laid out, in pixels: what a layout can be asked, with room to spare. */
private const val MaxItemHeight = 1 shl 17

/**
 * The room of a page in the strip.
 *
 * @property height how high the page is, as wide as the strip, in pixels.
 * @property picture the size of its picture, when it is drawn in slices.
 * @property slices the slices it is drawn in; empty for a page drawn whole, or not there yet.
 * @property sample what its slices are decoded at ([sliceSample]).
 */
internal data class StripRoom(
    val height: Int,
    val picture: IntSize = IntSize.Zero,
    val slices: List<StripSlice> = emptyList(),
    val sample: Int = 1,
) {
    /** How many items of the strip the page is. */
    val items: Int get() = slices.size.coerceAtLeast(1)

    /**
     * How high the item [slice] of the page is, in pixels. Slices are cut where their rows fall in the page's
     * height, so that they add up to it to the pixel: none overlaps the next, and no line shows between two.
     */
    fun heightOf(slice: Int): Int {
        val high = if (slices.isEmpty()) height else bottomOf(slice) - bottomOf(slice - 1)
        return high.coerceIn(0, MaxItemHeight)
    }

    private fun bottomOf(slice: Int): Int =
        slices.getOrNull(slice)?.let { (height.toLong() * it.bottom / picture.height).toInt() } ?: 0
}

/**
 * The strip laid out: its items, and the room of each page.
 *
 * @property width how wide the strip is, in pixels.
 */
internal class StripFrame(val width: Int, val rooms: List<StripRoom>) {
    val layout = StripLayout(rooms.map { it.items })

    /**
     * What tells the item [item] from the others: its page and its slice. An item keeps its key when a page above
     * it turns out to be several slices: the list stays on what it shows.
     */
    fun keyOf(item: Int): Long = stripKey(layout.pageOf(item), layout.sliceOf(item))
}

private const val PageShift = 32

/** The key of the slice [slice] of [page] in the list of the strip. */
internal fun stripKey(page: Int, slice: Int): Long = (page.toLong() shl PageShift) or slice.toLong()

/** The page of the item of the list whose [key] is given; null for a key that is not one of the strip's. */
internal fun pageOfStripKey(key: Any): Int? = (key as? Long)?.let { (it shr PageShift).toInt() }

/**
 * What the strip learnt of the pictures of its pages. The reader tells only the pages near the one read; the
 * size of a picture is kept here once seen, so that a page left behind keeps its height, and the strip does not
 * move under the finger when the reader forgets it.
 */
internal class StripPictures {
    private val sizes = HashMap<Int, IntSize>()

    /** The pages whose picture could not be read in parts: they are drawn whole. */
    var whole: Set<Int> by mutableStateOf(emptySet())
        private set

    /** The picture of [page] cannot be read in parts. */
    fun unreadable(page: Int) {
        whole = whole + page
    }

    /**
     * The strip of an album of [pageCount] pages, of which the reader has [pages], in a [window] as wide as the
     * strip and as high as the screen, in pixels. A page whose picture was never seen takes the shape of the
     * nearest one that was ([provisionalShape]), no higher than the window: its loading mark, or what went wrong
     * and the button to try again, are in the middle of its room, and next to a page many screens high they
     * would be as far down.
     */
    fun frame(pages: Map<Int, PageState>, pageCount: Int, window: IntSize): StripFrame {
        pages.forEach { (index, page) ->
            if (page is PageState.Ready && page.width > 0 && page.height > 0) {
                sizes[index] = IntSize(page.width, page.height)
            }
        }
        val shapes = sizes.mapValues { (_, size) -> size.width.toFloat() / size.height }
        val drawnWhole = whole
        val rooms = List(pageCount) { index ->
            val seen = shapes[index]
            val shape = seen ?: provisionalShape(shapes, index, window.width.toFloat(), window.height.toFloat())
            val highest = if (seen == null && window.height > 0) window.height else Int.MAX_VALUE
            val height = (window.width / shape).roundToInt().coerceIn(1, highest)
            val size = sizes[index]?.takeIf { index !in drawnWhole }
            val slices = size?.let { stripSlices(it.width, it.height, window.width) }.orEmpty()
            when {
                size == null || slices.size < 2 -> StripRoom(height)
                else -> StripRoom(height, size, slices, sliceSample(size.width, window.width))
            }
        }
        return StripFrame(window.width, rooms)
    }
}
