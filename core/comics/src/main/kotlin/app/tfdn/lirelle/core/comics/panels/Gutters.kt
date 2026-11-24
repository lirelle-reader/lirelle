package app.tfdn.lirelle.core.comics.panels

import kotlin.math.abs

/** Colours are told apart by this many values of each channel together. */
private const val Shade = 16
private const val Shades = 256 / Shade
private const val Levels = 256
private const val Keys = Shades * Shades * Shades

/**
 * The colour of the gutters, as red, green and blue: the most frequent colour of the page's border band, colours
 * being told apart by sixteenths of each channel; then, of the pixels of that colour, the middle value of each
 * channel.
 */
internal fun gutterColour(page: PagePixels): IntArray {
    val band = maxOf(Cut.BandLeast, Math.rint(minOf(page.width, page.height) * Cut.Band).toInt())
    val counts = IntArray(Keys)
    forEachBandPixel(page, band) { counts[keyOf(it)]++ }
    // The first of the most frequent, as the reference takes it.
    var best = 0
    for (key in 1 until Keys) if (counts[key] > counts[best]) best = key
    val reds = IntArray(Levels)
    val greens = IntArray(Levels)
    val blues = IntArray(Levels)
    forEachBandPixel(page, band) {
        if (keyOf(it) == best) {
            reds[red(it)]++
            greens[green(it)]++
            blues[blue(it)]++
        }
    }
    return intArrayOf(middle(reds, counts[best]), middle(greens, counts[best]), middle(blues, counts[best]))
}

private fun keyOf(argb: Int): Int =
    red(argb) / Shade * (Shades * Shades) + green(argb) / Shade * Shades + blue(argb) / Shade

/** The middle of [count] values given by how many there are of each: of two middle ones, their mean, cut down. */
private fun middle(histogram: IntArray, count: Int): Int =
    (ranked(histogram, (count - 1) / 2) + ranked(histogram, count / 2)) / 2

/** The value at [rank], from 0, among those the [histogram] counts, in order. */
private fun ranked(histogram: IntArray, rank: Int): Int {
    var seen = 0
    var value = 0
    while (value < histogram.lastIndex) {
        seen += histogram[value]
        if (seen > rank) break
        value++
    }
    return value
}

/** The top rows, the bottom rows, then the left and right columns between them: each pixel once. */
private inline fun forEachBandPixel(page: PagePixels, band: Int, each: (Int) -> Unit) {
    val w = page.width
    val h = page.height
    for (y in 0 until band) for (x in 0 until w) each(page.argb[y * w + x])
    for (y in h - band until h) for (x in 0 until w) each(page.argb[y * w + x])
    for (y in band until h - band) {
        for (x in 0 until band) each(page.argb[y * w + x])
        for (x in w - band until w) each(page.argb[y * w + x])
    }
}

/** Where the page is the [colour], each channel within the tolerance. */
internal fun nearColour(page: PagePixels, colour: IntArray): Plane {
    val (r, g, b) = colour
    return Plane(
        page.width,
        page.height,
        BooleanArray(page.argb.size) {
            val p = page.argb[it]
            abs(red(p) - r) <= Cut.Tolerance && abs(green(p) - g) <= Cut.Tolerance && abs(blue(p) - b) <= Cut.Tolerance
        },
    )
}

/**
 * The gutters: what is gutter-coloured ([near]) and reaches the margin. The page is padded with gutter first, so
 * that the gutters on both sides of a panel reaching the page's edge join around it; the flood goes through
 * passages at least [Cut.Leak] wide only. Small thin
 * blobs lying in the gutters are gutter, and what sticks out thinly from a panel is dropped.
 */
internal fun outerBackground(near: Plane, width: Int, height: Int): Plane {
    val outer = reachedFromMargin(near, width, height)
    fillSpecks(outer)
    // An odd box: an even one would shift what it opens by one pixel.
    val box = maxOf(Cut.OpeningLeast, Math.rint(Cut.Opening * maxOf(width, height)).toInt()) or 1
    val panels = outer.not().boxed(box, box, erode = true).boxed(box, box, erode = false)
    return panels.not()
}

/** What is gutter-coloured and joined to the margin by passages at least [Cut.Leak] wide. */
private fun reachedFromMargin(near: Plane, width: Int, height: Int): Plane {
    val pad = Cut.Pad
    val padded = Plane(width + 2 * pad, height + 2 * pad)
    padded.bits.fill(true)
    for (y in 0 until height) {
        near.bits.copyInto(padded.bits, (y + pad) * padded.width + pad, y * width, (y + 1) * width)
    }
    val reached = padded.boxed(Cut.Leak, Cut.Leak, erode = true)
        .floodedFromCorner()
        .boxed(Cut.Leak, Cut.Leak, erode = false)
    val outer = Plane(width, height)
    for (y in 0 until height) {
        val row = (y + pad) * padded.width + pad
        for (x in 0 until width) outer.bits[y * width + x] = reached.bits[row + x] && near.bits[y * width + x]
    }
    return outer
}

/**
 * Makes gutter of the small thin blobs lying in the gutters ([outer]): text, a page number. A blob is told by its
 * box, and its own cells are what becomes gutter, not the box.
 */
internal fun fillSpecks(outer: Plane) {
    val side = maxOf(outer.width, outer.height)
    for (blob in outer.not().takeBlobs()) {
        val w = blob.box.width
        val h = blob.box.height
        if (minOf(w, h) <= Cut.SpeckSide * side && w.toDouble() * h <= Cut.SpeckArea * outer.height * outer.width) {
            blob.forEachCell { outer.bits[it] = true }
        }
    }
}
