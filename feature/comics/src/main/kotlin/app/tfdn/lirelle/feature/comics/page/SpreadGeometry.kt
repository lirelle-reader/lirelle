package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import app.tfdn.lirelle.core.comics.layout.Corner
import app.tfdn.lirelle.core.comics.settings.Fit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Where a spread stands in the screen. Lengths are pixels. A spread is laid out once at its fitted size, then
 * drawn at a zoom and at an offset: the place of its top left corner, from the top left corner of the screen.
 */

/** The fit is the lowest zoom, and four times it the highest. */
internal const val MinZoom = 1f
internal const val MaxZoom = 4f

/** What a long press zooms to. */
internal const val LongPressZoom = 2f

/** No image is asked with a side longer than this: past it a screen cannot draw it, and it would fill the memory. */
private const val MaxDecodeSide = 4096

/**
 * The shape of each page of a spread, as its width over its height. [sizes] are the sizes of the images, null
 * for a page that is not on the device: it takes the shape of the page beside it, or with nothing to go by its
 * share of the [viewport], so that what stands in its place has the room of a page.
 */
internal fun pageShapes(sizes: List<Size?>, viewport: Size): List<Float> {
    val known = sizes.map { size -> size?.takeIf { it.width > 0f && it.height > 0f }?.let { it.width / it.height } }
    val laidOut = viewport.width > 0f && viewport.height > 0f
    val share = if (laidOut) viewport.width / sizes.size / viewport.height else 1f
    return known.map { it ?: known.firstNotNullOfOrNull { other -> other } ?: share }
}

/**
 * The fit a spread is shown at, when the settings say [fit]: the fit to the width is for a reader that shows one
 * page at a time. With two pages ([twoPages]) the fit is always the whole pages, for a page that stands alone as
 * well: the cover, a wide page, a last page without its pair.
 */
internal fun spreadFit(fit: Fit, twoPages: Boolean): Fit = if (twoPages) Fit.Page else fit

/**
 * The size of a spread at the fit: its pages, of the [shapes] given, side by side at the same height and without
 * a gap. [Fit.Page] puts all of it inside the [viewport]; [Fit.Width] makes one page as wide as the viewport,
 * however high, and is [Fit.Page] for two pages. [fit] is the one of [spreadFit].
 */
internal fun fittedSize(shapes: List<Float>, viewport: Size, fit: Fit): Size {
    val shape = shapes.sum()
    if (shape <= 0f) return Size.Zero
    val widthFirst = (fit == Fit.Width && shapes.size == 1) || viewport.width / shape <= viewport.height
    return when {
        widthFirst -> Size(viewport.width, viewport.width / shape)
        else -> Size(viewport.height * shape, viewport.height)
    }
}

/**
 * The room of each page of a spread [width] wide, from its left to its right: its share of the width, by its
 * shape among the [shapes].
 */
internal fun pageSpans(shapes: List<Float>, width: Float): List<PageSpan> {
    val edges = shapes.runningFold(0f) { edge, shape -> edge + width * shape / shapes.sum() }
    return edges.zipWithNext(::PageSpan)
}

/** The offsets a spread may have, from [min] to [max] on each axis. */
internal data class PanBounds(val min: Offset, val max: Offset) {
    /** The offset inside the bounds that is nearest to [offset]. */
    fun clamp(offset: Offset): Offset =
        Offset(offset.x.coerceIn(min.x, max.x), offset.y.coerceIn(min.y, max.y))

    /** The offset that shows the [corner] of the spread. */
    fun at(corner: Corner): Offset =
        Offset(if (corner.left) max.x else min.x, if (corner.top) max.y else min.y)

    /** Where the spread stands: where it was moved to ([pan]), or at its opening [corner] when nobody moved it. */
    fun place(pan: Offset?, corner: Corner): Offset = if (pan == null) at(corner) else clamp(pan)
}

/**
 * How far a spread of the fitted size [content] can be moved at [zoom]. On an axis where it is smaller than the
 * [viewport] it stays in the middle; where it is larger it moves until its edge reaches the viewport's.
 */
internal fun panBounds(content: Size, viewport: Size, zoom: Float): PanBounds {
    val slackX = viewport.width - content.width * zoom
    val slackY = viewport.height - content.height * zoom
    return PanBounds(
        min = Offset(if (slackX >= 0f) slackX / 2 else slackX, if (slackY >= 0f) slackY / 2 else slackY),
        max = Offset(max(slackX / 2, 0f), max(slackY / 2, 0f)),
    )
}

/** The offset that keeps what is under [point] there while the zoom goes [from] one value [to] another. */
internal fun zoomedAround(point: Offset, offset: Offset, from: Float, to: Float): Offset =
    point - (point - offset) * (to / from)

/**
 * The size to decode an [image] at, for a page drawn in [slot] at the fit: large enough for the highest zoom,
 * never larger than the image, and never past [MaxDecodeSide]. The shape of the image is kept.
 */
internal fun decodeSize(image: IntSize, slot: Size): IntSize {
    if (image.width <= 0 || image.height <= 0) return image
    val scale = min(drawScale(image, slot, MaxZoom), 1f)
    return IntSize(
        width = (image.width * scale).roundToInt().coerceAtLeast(1),
        height = (image.height * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * The long side the picture of a page needs to be sharp at [zoom], for a page drawn in [slot] at the fit and
 * whose picture is now [image]: that of its room at that zoom, never past [MaxDecodeSide]. It may be more than
 * the picture has: a page drawn on the device can be drawn again that large. 0 for a picture without a size.
 */
internal fun sharpSide(image: IntSize, slot: Size, zoom: Float): Int {
    if (image.width <= 0 || image.height <= 0) return 0
    return (max(image.width, image.height) * drawScale(image, slot, zoom)).roundToInt()
}

/**
 * Whether an [image] is too small for a page that needs [side] pixels on its long side, as [sharpSide] says:
 * by more than the rounding of its sides leaves. A picture drawn for a size has its short side rounded to a
 * whole pixel, so that its room, which has its shape, can be a pixel or two away from it.
 */
internal fun tooSmallFor(image: IntSize, side: Int): Boolean {
    val long = max(image.width, image.height)
    val short = min(image.width, image.height)
    if (short <= 0) return false
    return side > long + ceil(long.toFloat() / short).toInt()
}

/**
 * How many times its size an [image] is to be for its page, drawn in [slot] at the fit, to be shown at [zoom]
 * with a pixel of the picture for each one of the screen, no side going past [MaxDecodeSide].
 */
private fun drawScale(image: IntSize, slot: Size, zoom: Float): Float {
    val longest = max(image.width, image.height).toFloat()
    return min(min(slot.width * zoom / image.width, slot.height * zoom / image.height), MaxDecodeSide / longest)
}
