package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.PageState
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How many pages before and after those of the strip are followed: they are in view after a short scroll. */
private const val StripMargin = 2

/**
 * How many pages after a page the pages fetch ahead of it once they jumped there or settled on it: the reach of
 * the window of `core.comics` (`fetch/Window.kt`), which this module does not see. A strip further than that from
 * where they last did has left everything they fetched behind.
 */
private const val FetchReach = 5

/**
 * Follows what the device has of the pages near the reader: those of the spread shown, which are asked
 * for at once, and those of the spreads before and after it, which a swipe brings in. A page that turns
 * out wider than it is high is told to the spreads, which pair again around it.
 *
 * Read vertically, the pages asked for at once are all those the strip shows, then the few that follow them,
 * so that they are there before they scroll in; none behind. The pages near are those and a few around them. A
 * strip flung far has the pages jump to the page at its top: asked for one by one, every page it passed would be
 * fetched before the ones it stops on.
 */
internal class PageWatch(private val core: ComicCore) {
    private val watched = mutableMapOf<Int, Job>()

    /**
     * The pages last asked for: a spread is asked once, however many times the spreads are worked out, and a page
     * of the strip once for as long as it stays on screen or just after it.
     */
    private var shown: List<Int> = emptyList()

    /** The spreads, or the one shown, may have changed. It may be called again from within itself. */
    fun refresh() {
        val pages = core.pages ?: return
        val state = core.current
        val now = state.spread?.let { if (state.vertical) asked(state) else it.pages }.orEmpty()
        if (now != shown) {
            val before = shown.toSet()
            shown = now
            when {
                !state.vertical -> now.forEach(pages::show)
                farFrom(now.first()) -> {
                    // What waits, and what is fetched for the pages passed, is dropped: the page at the top
                    // first. The others are all asked again: one of them may have been dropped.
                    core.fetchedAround = now.first()
                    pages.jump(now.first())
                    now.drop(1).forEach(pages::show)
                }
                // A strip moves a page at a time: the pages that stay on screen were asked already.
                else -> (now - before).forEach(pages::show)
            }
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

    /** The pages of the strip asked for, in their order: those on screen, then those that follow them. */
    private fun asked(state: ComicReaderUiState): List<Int> {
        val strip = onStrip()
        return (strip.first..strip.last + StripMargin).filter { it < state.pageCount }
    }

    /**
     * Whether the page [top], at the top of the strip, is past the pages fetched around the one the pages last
     * jumped to or settled on. The first page a strip shows is where they start from.
     */
    private fun farFrom(top: Int): Boolean {
        val around = core.fetchedAround ?: top.also { core.fetchedAround = it }
        return abs(top - around) > FetchReach
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
