package app.tfdn.lirelle.feature.comics.strip

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/*
 * The rules of the strip of an album read vertically, its pages one under the other, each as wide as the strip:
 * what a tap asks, the shape of a page that is not there yet, the slices a page too tall for one picture is
 * drawn in, and the items those make of the strip.
 */

/** The share of the screen's height, at its top and at its bottom, where a tap moves the strip. */
private const val TapEdge = 0.25f

/** The share of the screen a tap moves the strip by: the rest stays in view, to keep the thread. */
private const val StepShare = 0.9f

/** The most pixels a picture decoded whole has on a side: the page view's limit. */
private const val MaxWholeSide = 4096

/**
 * The most rows of the screen a slice takes once drawn. Decoded at its [sliceSample], which is under twice too
 * large, a slice stays under [MaxWholeSide] rows.
 */
private const val MaxSliceRows = 2048

/** What a tap on the strip asks. */
internal enum class StripTap { Back, Bars, Forward }

/** What a tap at [y] asks, in a screen [height] high: back at the top, forward at the bottom, the bars between. */
internal fun stripTap(y: Float, height: Float): StripTap = when {
    height <= 0f -> StripTap.Bars
    y < height * TapEdge -> StripTap.Back
    y >= height * (1 - TapEdge) -> StripTap.Forward
    else -> StripTap.Bars
}

/** How far a tap moves the strip, in a screen [viewportHeight] high. */
internal fun stripStep(viewportHeight: Float): Float = viewportHeight * StepShare

/**
 * The shape (width over height) a page that is not on the device is given meanwhile: that of the nearest page
 * whose shape is in [shapes], the one before at equal distance; with none known, one screen high.
 */
internal fun provisionalShape(shapes: Map<Int, Float>, index: Int, stripWidth: Float, viewportHeight: Float): Float {
    val nearest = shapes.keys.minWithOrNull(compareBy<Int> { abs(it - index) }.thenBy { it })
    return when {
        nearest != null -> shapes.getValue(nearest)
        viewportHeight > 0f && stripWidth > 0f -> stripWidth / viewportHeight
        else -> 1f
    }
}

/** Rows of a page's picture, from [top] to [bottom] (not included), in the picture's own pixels. */
internal data class StripSlice(val top: Int, val bottom: Int)

/**
 * The slices a picture of [width] by [height] pixels is drawn in, [drawnWidth] pixels wide. One, the whole
 * picture, when it can be decoded whole at that width within the page view's limit; else slices that follow one
 * another from the top, each no higher than the limit of a slice once drawn.
 */
internal fun stripSlices(width: Int, height: Int, drawnWidth: Int): List<StripSlice> {
    val whole = listOf(StripSlice(0, height.coerceAtLeast(0)))
    if (width <= 0 || height <= 0 || drawnWidth <= 0) return whole
    val scale = min(1f, drawnWidth.toFloat() / width)
    if (height * scale <= MaxWholeSide) return whole
    val rows = (MaxSliceRows / scale).toInt().coerceAtLeast(1)
    val count = ceil(height.toFloat() / rows).toInt()
    return (0 until count).map { StripSlice(it * rows, min((it + 1) * rows, height)) }
}

/**
 * What a slice of a picture [width] pixels wide is decoded at, drawn [drawnWidth] pixels wide: the largest power
 * of two that leaves it at least as wide as it is drawn.
 */
internal fun sliceSample(width: Int, drawnWidth: Int): Int {
    if (drawnWidth <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= drawnWidth) sample *= 2
    return sample
}

/**
 * The items of the strip: the pages one after the other, each as many items as it has slices.
 *
 * @param slices how many slices each page has, by page; one for a page drawn whole or not there yet.
 */
internal class StripLayout(slices: List<Int>) {
    private val starts = slices.runningFold(0) { start, count -> start + count.coerceAtLeast(1) }
    private val pages = slices.size

    /** How many items the strip has. */
    val size: Int = starts.last()

    /** The first item of [page]; of the nearest page for one outside the album, 0 for an album without a page. */
    fun itemOf(page: Int): Int = if (pages == 0) 0 else starts[page.coerceIn(0, pages - 1)]

    /** The page the item [item] is part of; the nearest end for an item outside the strip. */
    fun pageOf(item: Int): Int {
        if (pages == 0) return 0
        val found = starts.binarySearch(item.coerceIn(0, size - 1))
        return (if (found >= 0) found else -found - 2).coerceIn(0, pages - 1)
    }

    /** Which slice of its page the item [item] is, from 0. */
    fun sliceOf(item: Int): Int = item.coerceIn(0, (size - 1).coerceAtLeast(0)) - itemOf(pageOf(item))
}
