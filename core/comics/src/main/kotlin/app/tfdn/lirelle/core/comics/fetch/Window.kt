package app.tfdn.lirelle.core.comics.fetch

/**
 * Forward first, the page before close behind: the reader goes on, but may look back at what happened a
 * page earlier, on a device that kept nothing of it.
 */
private val WindowOffsets = listOf(0, 1, 2, -1, 3, -2, 4, -3, 5, -4, -5)

/**
 * The pages fetched ahead around [index], in the order they are asked for; those outside an album of
 * [pageCount] pages are left out.
 */
internal fun windowOrder(index: Int, pageCount: Int): List<Int> =
    WindowOffsets.map { index + it }.filter { it in 0 until pageCount }
