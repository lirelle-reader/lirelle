package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.layout.spreadIndexOf
import app.tfdn.lirelle.feature.comics.end.EndOfAlbum

/**
 * Following the strip of an album read vertically, which has no page turned: the page at the top of the strip is
 * the spread shown, told to the pages as it scrolls, which fetch it, and to the session only once the strip came
 * to rest ([Turning.arrived]). Past the bottom of the last page is the end of the album. An album whose pages are
 * turned has no strip: what one tells is ignored.
 */
internal class Scrolling(
    private val core: ComicCore,
    private val turning: Turning,
    private val bars: Bars,
    private val end: EndOfAlbum,
) : StripNavigation {
    private val following: Boolean get() = core.ready && core.current.vertical

    override fun stripShows(first: Int, last: Int) {
        if (!following) return
        val end = core.current.pageCount - 1
        val page = first.coerceIn(0, end)
        core.strip = page..last.coerceIn(page, end)
        read(page)
    }

    override fun stripRested() {
        if (following && core.page != turning.told) turning.arrived(moved = true)
    }

    override fun stripPastEnd() {
        if (!following) return
        bars.hide()
        val last = core.current.pageCount - 1
        // The last page is the page read, wherever it is on screen: the short pages above it stay shown.
        val first = core.strip.takeIf { !it.isEmpty() && core.page in it }?.first ?: last
        core.strip = first..last
        read(last)
        // As a turn past the last spread does: the end is written before the panel waits for the server to have it.
        if (!turning.endWritten) turning.arrived(moved = true)
        end.onPastEnd()
    }

    /** [page] is the page read: the spread shown is its own, and the pages are told what the strip shows. */
    private fun read(page: Int) {
        if (page != core.page) {
            core.page = page
            core.update { it.copy(spreadIndex = spreadIndexOf(it.spreads, page), forward = true) }
        }
        core.onLayout()
    }
}
