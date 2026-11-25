package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.layout.pageNumberOf
import app.tfdn.lirelle.core.comics.layout.spreadIndexOf
import app.tfdn.lirelle.feature.comics.end.EndOfAlbum
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The bars over the page: shown when the album opens, hidden by themselves a moment later, then shown and
 * hidden by the user.
 */
internal class Bars(private val core: ComicCore, private val timings: ComicTimings) {
    private var hiding: Job? = null

    /** The album is open. */
    fun open() {
        hiding?.cancel()
        core.update { it.copy(barsShown = true) }
        hiding = core.scope.launch {
            delay(timings.bars)
            core.update { it.copy(barsShown = false) }
        }
    }

    fun toggle() {
        stay()
        core.update { it.copy(barsShown = !it.barsShown) }
    }

    /** The user is reading on: a page turned, or moved under a tap on its edge. The bars go. */
    fun hide() {
        stay()
        core.update { it.copy(barsShown = false) }
    }

    /** The user is using the bars: they no longer hide by themselves. */
    fun stay() {
        hiding?.cancel()
    }
}

/**
 * Moving from spread to spread: each spread reached is told to the pages, which fetch it, and to the
 * session, which keeps the position. Past the last one is the end of the album. A turn of the page hides the
 * bars; the slider, which is in them, does not.
 */
internal class Turning(
    private val core: ComicCore,
    private val timings: ComicTimings,
    private val bars: Bars,
    private val end: EndOfAlbum,
) : ComicNavigation {
    private var settling: Job? = null

    /** Whether the end of the album is the position written: the user turned to its last spread, or past it. */
    var endWritten = false
        private set

    /** The page last told to the session: a strip at rest on it tells nothing more. */
    var told = -1
        private set

    override fun next() {
        if (!core.ready) return
        bars.hide()
        val state = core.current
        if (state.spreadIndex >= state.spreads.lastIndex) {
            // An album opened on the spread of its last page was not turned to it, and a page may have become
            // part of the last spread since it was: the end is written now, before the panel waits for the
            // server to have it.
            if (!endWritten) arrived(moved = true)
            end.onPastEnd()
        } else {
            show(state.spreadIndex + 1, forward = true)
        }
    }

    override fun previous() {
        if (!core.ready) return
        bars.hide()
        val index = core.current.spreadIndex
        if (index > 0) show(index - 1, forward = false)
    }

    override fun goTo(pageIndex: Int) {
        if (!core.ready) return
        core.update { it.copy(previewPage = null) }
        if (spreadIndexOf(core.current.spreads, pageIndex) != core.current.spreadIndex) land(pageIndex, moved = true)
    }

    override fun previewPage(pageIndex: Int?) {
        if (!core.ready) return
        bars.stay()
        core.update { it.copy(previewPage = pageIndex?.coerceIn(0, it.pageCount - 1)) }
    }

    override fun retryPage(index: Int) {
        core.pages?.retry(index)
    }

    override fun sharpenPage(index: Int, longSidePx: Int) {
        core.pages?.sharpen(index, longSidePx)
    }

    /**
     * Goes straight to the spread that shows [pageIndex], at its beginning: what the pages were fetching
     * for elsewhere is dropped. Without [moved] the user did not turn there: it is not written. The strip of an
     * album read vertically is told where to scroll ([ComicReaderUiState.landing]).
     */
    fun land(pageIndex: Int, moved: Boolean) {
        show(spreadIndexOf(core.current.spreads, pageIndex), forward = true, moved = moved)
        // A jump fetches around the page by itself.
        settling?.cancel()
        core.pages?.jump(core.page)
        core.update { it.copy(landing = Landing(core.page, it.landing.serial + 1)) }
    }

    /**
     * The spread shown, at opening and after each move, is where the reader is: the session is told, and
     * the pages around are fetched once the reader stayed there.
     */
    fun arrived(moved: Boolean) {
        val state = core.current
        val spread = state.spread ?: return
        // The pages shown count as read: the last spread is the whole album, with one page or two.
        val fraction = (spread.pages.last() + 1).toDouble() / state.pageCount
        endWritten = moved && spread.pages.last() == state.pageCount - 1
        told = spread.first
        end.onPosition(fraction)
        core.scope.launch { core.session?.relocated(pageNumberOf(spread.first), fraction, moved) }
        settling?.cancel()
        settling = core.scope.launch {
            delay(timings.settle)
            core.pages?.settle(spread.first)
        }
    }

    private fun show(index: Int, forward: Boolean, moved: Boolean = true) {
        val spread = core.current.spreads.getOrNull(index) ?: return
        core.page = spread.first
        core.update { it.copy(spreadIndex = index, forward = forward) }
        core.onLayout()
        arrived(moved)
    }
}
