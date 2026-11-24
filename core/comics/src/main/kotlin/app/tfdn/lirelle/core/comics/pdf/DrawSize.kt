package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.fetch.ImageSize

/**
 * The long side, in pixels, a page is first drawn for, before the page view says what its room needs: enough
 * for most screens, and the view asks for more where it is not.
 */
internal const val FirstLongSidePx = 2048

/** The long side past which a page is never drawn: the page view shows no larger picture. */
internal const val MaxLongSidePx = 4096

/**
 * The size in pixels of a page of [points] drawn so that its long side has [longSidePx] pixels. The short side
 * keeps a pixel at least, and a page that says no size is drawn as a square.
 */
internal fun pixelSize(points: ImageSize, longSidePx: Int): ImageSize {
    val long = maxOf(points.width, points.height).toLong()
    if (long <= 0) return ImageSize(longSidePx, longSidePx)
    fun scaled(side: Int): Int = ((side * longSidePx + long / 2) / long).toInt().coerceAtLeast(1)
    return ImageSize(scaled(points.width), scaled(points.height))
}

/** The long side a page is drawn for when [asked] is wanted: never under the first drawing, never past the limit. */
internal fun boundedLongSide(asked: Int): Int = asked.coerceIn(FirstLongSidePx, MaxLongSidePx)

/**
 * Whether a page kept with [keptLongSide] pixels on its long side, or not kept at all when null, is to be
 * drawn for [askedLongSide]: only for a larger picture than the one there is.
 */
internal fun needsDrawing(keptLongSide: Int?, askedLongSide: Int): Boolean =
    keptLongSide == null || boundedLongSide(askedLongSide) > keptLongSide
