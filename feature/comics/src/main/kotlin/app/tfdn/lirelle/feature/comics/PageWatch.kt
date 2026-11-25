package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.PageState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How many pages before and after those of the strip are followed: they are in view after a short scroll. */
private const val StripMargin = 2

/**
 * Follows what the device has of the pages near the reader: those of the spread shown, which are asked
 * for at once, and those of the spreads before and after it, which a swipe brings in. A page that turns
 * out wider than it is high is told to the spreads, which pair again around it. Read vertically, the pages
 * asked for at once are all those the strip shows, and the pages near are those and a few around them.
 */
internal class PageWatch(private val core: ComicCore) {
    private val watched = mutableMapOf<Int, Job>()

    /**
     * The pages last asked for: a spread is asked once, however many times the spreads are worked out, and a page
     * of the strip once for as long as it stays on screen.
     */
    private var shown: List<Int> = emptyList()

    /** The spreads, or the one shown, may have changed. It may be called again from within itself. */
    fun refresh() {
        val pages = core.pages ?: return
        val state = core.current
        val now = state.spread?.let { if (state.vertical) onStrip().toList() else it.pages }.orEmpty()
        if (now != shown) {
            // A strip moves a page at a time: the pages that stay on screen were asked already.
            val asked = if (state.vertical) now - shown.toSet() else now
            shown = now
            asked.forEach(pages::show)
        }
        val near = if (state.vertical) nearStrip(state) else nearSpreads(state)
        (watched.keys - near).forEach { watched.remove(it)?.cancel() }
        core.update { it.copy(pages = it.pages.filterKeys(near::contains)) }
        // Each is in the map before it runs: a page that arrives at once, wide, has the spreads worked out again.
        val added = (near - watched.keys).map { index ->
            core.scope.launch(start = CoroutineStart.LAZY) { pages.page(index).collect { arrived(index, it) } }
                .also { watched[index] = it }
        }
        added.forEach { it.start() }
    }

    private fun nearSpreads(state: ComicReaderUiState): Set<Int> =
        (state.spreadIndex - 1..state.spreadIndex + 1).flatMap { state.spreads.getOrNull(it)?.pages.orEmpty() }.toSet()

    /** The pages the strip shows, from its top, or the one read while it told none (or told of elsewhere). */
    private fun onStrip(): IntRange = core.strip.takeIf { !it.isEmpty() && core.page in it } ?: core.page..core.page

    /** The pages the strip shows and those just before and after. */
    private fun nearStrip(state: ComicReaderUiState): Set<Int> {
        val shown = onStrip()
        return (shown.first - StripMargin..shown.last + StripMargin).filter { it in 0 until state.pageCount }.toSet()
    }

    private fun arrived(index: Int, state: PageState) {
        if (index !in watched) return
        core.update { it.copy(pages = it.pages + (index to state)) }
        val isWide = state is PageState.Ready && state.width > state.height
        if (isWide && core.wide.add(index)) core.layout()
    }
}
