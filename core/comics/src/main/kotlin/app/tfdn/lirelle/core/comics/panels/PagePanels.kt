package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.settings.Direction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Why a page is shown whole. */
enum class WholePage {
    /** The first page of the album: its cover. */
    Cover,

    /** No panels were found on it, or what was found does not make a page of panels. */
    NoPanels,

    /** Its file is no picture the cut can read. */
    NoPicture,

    /** The cut could not be done this time (no room, or it failed): it is tried again at another opening. */
    Failed,
}

/** What is known of the zones of a page. */
sealed interface PageZones {
    /** Not known: nobody asked, its picture is not on the device, or its turn has not come. */
    data object Unknown : PageZones

    /** Cut into its panels: two [zones] or more, in reading order. [millis] is how long the cut took, 0 when kept. */
    data class Cut(val zones: List<Zone>, val millis: Long) : PageZones

    /** Not cut: the page is read whole, and [why]. */
    data class Whole(val why: WholePage) : PageZones
}

/**
 * The zones of the pages of one album. Nothing is computed until [cutting] is turned on; then the pages whose
 * picture is known to be on the device are cut one at a time, apart from the main thread: the page told by [show]
 * first among them, then the pages around it in the order they are fetched ahead. No page is fetched for its zones.
 *
 * The first page of an album, its cover, is never cut: it is told [PageZones.Whole] as soon as the
 * cutting is on and the reader is near it, whether its picture is on the device or not.
 *
 * A picture is known to be on the device once its page was looked at: the reader is expected to look at the page
 * it shows before it tells [show], and that page is then the first cut. When it does not, a page around may be cut
 * before the page shown: that costs the wait of one cut, never the zones of another page.
 */
interface PagePanels {
    /** False for an album whose pages are never cut (a PDF): the reader offers no mode for it. */
    val cuts: Boolean

    /** What is known of the page [index], as it changes. */
    fun zones(index: Int): StateFlow<PageZones>

    /** Turns the cutting on or off. Off, what is being cut is finished and nothing more is. */
    fun cutting(on: Boolean)

    /** The reader is on [index] of an album of [pageCount] pages. */
    fun show(index: Int, pageCount: Int)

    /**
     * The album is read in [direction]: zones found for the other one are not this one's. Left to right until
     * told otherwise.
     */
    fun direction(direction: Direction)

    /** Stops everything: no other page is cut, and what the cut under way finds is neither kept nor given. */
    fun close()
}

/** Opens the zones of an album whose [pages] are open; the cuts run in [scope]. */
fun interface PagePanelsOpener {
    fun open(key: ComicKey, pages: ComicPages, scope: CoroutineScope): PagePanels
}
