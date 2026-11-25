package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.layout.pageNumberOf
import app.tfdn.lirelle.core.library.model.ReadingPosition
import kotlinx.coroutines.launch

/** What the screen and its window tell the reader, and the newer position the server may hold meanwhile. */
internal class ComicHost(
    private val core: ComicCore,
    private val turning: Turning,
    private val scrolling: Scrolling,
    private val network: NetworkReturn,
) :
    ComicHostEvents,
    NewerPageActions {

    override fun windowSize(widthDp: Int, heightDp: Int) {
        core.widthDp = widthDp
        core.heightDp = heightDp
        core.layout()
    }

    override fun buildDebuggable(debuggable: Boolean) {
        core.debuggable = debuggable
    }

    override fun onForeground() {
        network.foreground()
        val session = core.session ?: return
        core.scope.launch { offer(session.resumed()) }
    }

    override fun onBackground() {
        // A strip sent behind while it moves told no rest: the page at its top is written first.
        scrolling.stripRested()
        network.background()
        core.session?.paused()
    }

    override fun acceptNewer() {
        val offered = core.offered ?: return
        val session = core.session ?: return
        offer(null)
        core.scope.launch {
            session.accept(offered)
            // The server's position is followed, not written again.
            turning.land(indexOf(offered), moved = false)
        }
    }

    override fun ignoreNewer() {
        core.offered?.let { core.session?.ignore(it) }
        offer(null)
    }

    private fun offer(position: ReadingPosition?) {
        core.offered = position
        core.update { it.copy(newerPage = position?.let { p -> pageNumberOf(indexOf(p)) }) }
    }

    private fun indexOf(position: ReadingPosition): Int =
        pageIndexAt(position.page, position.percent, core.current.pageCount)
}
