package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.PageState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Follows what the device has of the pages near the reader: those of the spread shown, which are asked
 * for at once, and those of the spreads before and after it, which a swipe brings in. A page that turns
 * out wider than it is high is told to the spreads, which pair again around it.
 */
internal class PageWatch(private val core: ComicCore) {
    private val watched = mutableMapOf<Int, Job>()

    /** The pages last asked for: a spread is asked once, however many times the spreads are worked out. */
    private var shown: List<Int> = emptyList()

    /** The spreads, or the one shown, may have changed. It may be called again from within itself. */
    fun refresh() {
        val pages = core.pages ?: return
        val state = core.current
        val now = state.spread?.pages.orEmpty()
        if (now != shown) {
            shown = now
            now.forEach(pages::show)
        }
        val near = (state.spreadIndex - 1..state.spreadIndex + 1)
            .flatMap { state.spreads.getOrNull(it)?.pages.orEmpty() }
            .toSet()
        (watched.keys - near).forEach { watched.remove(it)?.cancel() }
        core.update { it.copy(pages = it.pages.filterKeys(near::contains)) }
        // Each is in the map before it runs: a page that arrives at once, wide, has the spreads worked out again.
        val added = (near - watched.keys).map { index ->
            core.scope.launch(start = CoroutineStart.LAZY) { pages.page(index).collect { arrived(index, it) } }
                .also { watched[index] = it }
        }
        added.forEach { it.start() }
    }

    private fun arrived(index: Int, state: PageState) {
        if (index !in watched) return
        core.update { it.copy(pages = it.pages + (index to state)) }
        val isWide = state is PageState.Ready && state.width > state.height
        if (isWide && core.wide.add(index)) core.layout()
    }
}
