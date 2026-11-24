package app.tfdn.lirelle.core.comics.layout

import app.tfdn.lirelle.core.comics.settings.TwoPages

/** Under this height a window in landscape is a phone's: two pages would be small to read there. */
private const val TwoPagesMinHeightDp = 600

/** Whether a window of [widthDp] by [heightDp] shows two pages side by side. */
fun showsTwoPages(setting: TwoPages, widthDp: Int, heightDp: Int): Boolean = when (setting) {
    TwoPages.Automatic -> widthDp > heightDp && heightDp >= TwoPagesMinHeightDp
    TwoPages.Always -> widthDp > heightDp
    TwoPages.Never -> false
}

/**
 * What the screen shows at once: one page, or two side by side. [first] is read before [second],
 * whatever the reading direction; pages are indexes from 0.
 */
data class Spread(val first: Int, val second: Int? = null) {
    /** Its pages, in reading order. */
    val pages: List<Int> get() = listOfNotNull(first, second)
}

/**
 * The spreads of an album of [pageCount] pages, in reading order. Without [twoPages], one page each.
 * With: the cover alone, then pages by pairs; a page in [wide] (an image wider than it is high) is
 * always alone, and the pairs go on after it; a page left without a partner is alone.
 */
fun spreads(pageCount: Int, wide: Set<Int>, twoPages: Boolean): List<Spread> {
    if (!twoPages) return (0 until pageCount).map { Spread(it) }
    val spreads = mutableListOf<Spread>()
    var page = 0
    while (page < pageCount) {
        val next = page + 1
        val paired = page > 0 && page !in wide && next < pageCount && next !in wide
        spreads += Spread(page, next.takeIf { paired })
        page += if (paired) 2 else 1
    }
    return spreads
}

/**
 * The index in [spreads] of the spread that shows the page [pageIndex]; the nearest end for a page
 * outside the album, 0 when there is no spread.
 */
fun spreadIndexOf(spreads: List<Spread>, pageIndex: Int): Int {
    val index = spreads.indexOfFirst { pageIndex == it.first || pageIndex == it.second }
    return when {
        index >= 0 -> index
        spreads.isEmpty() || pageIndex < spreads.first().first -> 0
        else -> spreads.lastIndex
    }
}
