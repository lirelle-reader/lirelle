package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.downloads.DeviceCopyControl
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.launch

private val Stars = 1..5

/**
 * The panel at the end of the book, offered at each turn past the last page. The server marks the
 * book as read by itself; a rating chosen in the panel is sent when the panel is closed, as
 * [EndOfBookRating] describes: by "Close the book" or the next volume, which then leave the reader,
 * or by the system's back, a swipe down or a tap above the panel, which stay in it, on the last
 * page. Closing without stars sends nothing, and so does closing on the stars already sent; the
 * panel comes back with the stars it was left with.
 *
 * A book the device keeps has a row in the panel to delete it from there ([device]): the book is open, so it
 * goes when the reader is left, and until then the row offers to keep it after all.
 *
 * A source without ratings has a panel without stars, which takes none and sends none (spec 2c).
 */
internal class EndOfBook(
    private val core: ReaderCore,
    private val repository: ReaderRepository,
    changes: ReadingChanges,
    private val device: DeviceCopyControl,
) : EndPanelActions, DeviceCopyActions by device {
    private val rating = EndOfBookRating(core, repository, changes)

    init {
        // The row follows what the device keeps, while the panel is shown.
        core.scope.launch {
            device.copy.collect { copy -> core.update { it.copy(endPanel = it.endPanel?.copy(device = copy)) } }
        }
    }
    /** The stars the panel was left with, shown again when it comes back. */
    private var chosen: Int? = null

    /** The stars the server holds from this opening, null before any were sent. */
    private var sent: Int? = null

    /** The next volume, asked once: null until the server has answered, and when there is none. */
    private var nextVolume: NextVolume? = null
    private var nextAsked = false

    private val ratings: Boolean get() = core.capabilities.ratings

    /** The page shows the book: the reading a rating would be for is looked for, if the source has ratings. */
    fun onOpened() {
        if (ratings) rating.onOpened()
    }

    /** The page shows another place of the book, [fraction] of the way through it. */
    fun onPosition(fraction: Double) = rating.onPosition(fraction)

    /**
     * The user turned past the last page. Arriving at it opens nothing: its position was sent as any
     * other, and the server marked the book as read from it.
     */
    fun onPastEnd() {
        if (core.current.endPanel != null || !core.driver.opened) return
        val panel = EndPanel(rating = chosen, nextVolume = nextVolume, device = device.copy.value, ratings = ratings)
        core.update { it.copy(endPanel = panel, controlsShown = false) }
        val detail = core.book?.detail
        val seriesId = detail?.series?.id ?: return
        if (nextAsked) return
        nextAsked = true
        core.scope.launch {
            val next = (repository.nextVolume(seriesId, detail.id) as? ApiResult.Success)?.value
            nextVolume = next
            core.update { it.copy(endPanel = it.endPanel?.copy(nextVolume = next)) }
        }
    }

    override fun setRating(stars: Int?) {
        if (!ratings) return
        core.update { it.copy(endPanel = it.endPanel?.copy(rating = stars?.coerceIn(Stars), failed = false)) }
    }

    override fun closeEndPanel() = finish(then = ReaderEvent.Leave)

    override fun openNextVolume() {
        val next = core.current.endPanel?.nextVolume ?: return
        finish(then = ReaderEvent.OpenBook(next.bookId))
    }

    /**
     * The system's back, a swipe down or a tap above the panel: the rating is sent as by "Close the
     * book", but the reader stays, on the last page.
     */
    override fun dismissEndPanel() = finish(then = null)

    /** [then] is sent once the panel is closed: not while the rating is on its way, nor when it failed. */
    private fun finish(then: ReaderEvent?) {
        val panel = core.current.endPanel?.takeUnless { it.sending } ?: return
        core.scope.launch {
            val stars = panel.rating
            if (stars != null && stars != sent) {
                if (!rate(stars)) {
                    core.update { it.copy(endPanel = it.endPanel?.copy(sending = false, failed = true)) }
                    return@launch
                }
                sent = stars
            }
            chosen = stars
            core.update { it.copy(endPanel = null) }
            then?.let { core.events.trySend(it) }
        }
    }

    private suspend fun rate(stars: Int): Boolean {
        core.update { it.copy(endPanel = it.endPanel?.copy(sending = true, failed = false)) }
        return rating.rate(stars)
    }
}
