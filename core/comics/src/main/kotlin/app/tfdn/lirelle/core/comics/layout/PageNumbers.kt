package app.tfdn.lirelle.core.comics.layout

private const val Percent = 100f

/** The server's number of the page at [index]: it counts pages from 1. */
fun pageNumberOf(index: Int): Int = index + 1

/**
 * The index of the page the server calls [number], in an album of [pageCount] pages: the first page
 * when it gives none, the nearest one when the number is outside the album.
 */
fun pageIndexOf(number: Int?, pageCount: Int): Int =
    if (number == null) 0 else (number - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/**
 * How far in the album the page at [index] is, from 0 to 100, the page shown counting as read: the
 * last page is the whole album. 0 for an album without a page.
 */
fun percentOf(index: Int, pageCount: Int): Float =
    if (pageCount <= 0) 0f else ((index + 1) * Percent / pageCount).coerceIn(0f, Percent)
