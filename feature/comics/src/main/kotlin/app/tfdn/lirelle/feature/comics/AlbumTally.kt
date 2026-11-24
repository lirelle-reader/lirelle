package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage

/**
 * How many pages are counted before the album is judged. Six, as an album opened at its start begins with a cover
 * and a title page, which are never cut.
 */
private const val TallyPages = 6

/** How many of them may be cut, at most, in an album that lends itself badly to reading panel by panel. */
private const val TallyCut = 1

/**
 * Counts the pages read panel by panel in one opening of an album, each once, to say whether the album lends
 * itself badly to it: of its first pages read, next to none could be cut. Once that is said, or once enough pages
 * were cut for it never to be, nothing more is counted.
 */
internal class AlbumTally {
    private val counted = mutableSetOf<Int>()
    private var cut = 0
    private var over = false

    /**
     * The reader shows [page], of which [zones] is known. A page cut, or read whole because it is so, is counted;
     * one of which nothing is known, or whose cut failed, is not: it may be counted when it is seen again.
     *
     * @return true when this page makes the album one that lends itself badly: once at most.
     */
    fun see(page: Int, zones: PageZones?): Boolean {
        val isCut = zones is PageZones.Cut
        val isWhole = zones is PageZones.Whole && zones.why != WholePage.Failed
        val counts = isCut || isWhole
        if (over || !counts || !counted.add(page)) return false
        if (isCut) cut++
        over = cut > TallyCut || counted.size >= TallyPages
        return over && cut <= TallyCut
    }
}
