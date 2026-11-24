package app.tfdn.lirelle.core.comics.panels

private const val RedWeight = 4899
private const val GreenWeight = 9617
private const val BlueWeight = 1868
private const val Half = 8192
private const val Shift = 14
private const val Grey = 0xFF

/** The grey of each pixel, from 0 to 255 in a byte, as the reference's image library counts it. */
internal fun greys(page: PagePixels): ByteArray = ByteArray(page.argb.size) {
    val p = page.argb[it]
    ((red(p) * RedWeight + green(p) * GreenWeight + blue(p) * BlueWeight + Half) shr Shift).toByte()
}

/** The grey at [at], from 0 to 255. */
private fun ByteArray.grey(at: Int): Int = this[at].toInt() and Grey

/**
 * Where a pixel is on a thin stroke: darker, or lighter, by [Cut.StrokeContrast] than the pixels [Cut.StrokeReach]
 * away on both sides. First the strokes that run across the page (compared up and down), then those that run down
 * it (compared left and right). The darkest, or lightest, of the pixel and its two neighbours across the stroke is
 * what is compared; far pixels past an edge are taken from the other edge, as the reference does.
 */
internal fun strokes(grey: ByteArray, width: Int, height: Int): Pair<Plane, Plane> {
    val across = Plane(width, height)
    val along = Plane(width, height)
    val reach = Cut.StrokeReach
    // For each column, where its neighbours and the far columns are: the same on every row.
    val left = IntArray(width) { maxOf(0, it - 1) }
    val right = IntArray(width) { minOf(width - 1, it + 1) }
    val west = IntArray(width) { Math.floorMod(it - reach, width) }
    val east = IntArray(width) { Math.floorMod(it + reach, width) }
    for (y in 0 until height) {
        val row = y * width
        val up = maxOf(0, y - 1) * width
        val down = minOf(height - 1, y + 1) * width
        val before = Math.floorMod(y - reach, height) * width
        val after = Math.floorMod(y + reach, height) * width
        for (x in 0 until width) {
            val here = grey.grey(row + x)
            across.bits[row + x] =
                stands(here, grey.grey(up + x), grey.grey(down + x), grey.grey(before + x), grey.grey(after + x))
            along.bits[row + x] = stands(
                here,
                grey.grey(row + left[x]),
                grey.grey(row + right[x]),
                grey.grey(row + west[x]),
                grey.grey(row + east[x]),
            )
        }
    }
    return across to along
}

/** Whether a pixel, with its neighbours [one] and [other], stands out from the far pixels on both sides. */
private fun stands(here: Int, one: Int, other: Int, far: Int, farther: Int): Boolean =
    minOf(here, one, other) + Cut.StrokeContrast < minOf(far, farther) ||
        maxOf(here, one, other) - Cut.StrokeContrast > maxOf(far, farther)
