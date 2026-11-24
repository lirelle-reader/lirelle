package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.network.ApiError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Whether the open profile has a network, as it changes: what its profile says, or in a test a flow moved
 * by hand.
 */
class ComicNetwork(val online: Flow<Boolean>)

/** What a missing network leaves of a page, and of an album that could not be opened. */
private val PageUnreachable = PageState.Failed(ApiError.ServerUnreachable)
private val AlbumUnreachable = ComicFailure.Load(ApiError.ServerUnreachable)

/**
 * When the network comes back, asks again, once, for what failed for want of it: the album that could not
 * be opened, or the pages near the reader that could not be fetched. Only a reader in front asks; one that
 * was behind when the network came back asks as it comes to the front, if the network is still there.
 * What fails again says so as before, until the user asks or the network comes back once more.
 */
internal class NetworkReturn(
    private val core: ComicCore,
    private val network: ComicNetwork,
    private val opening: ComicOpening,
) {
    private var front = false

    /** The network came back while the reader was behind, and has not gone since. */
    private var owed = false

    /** Follows the network for as long as the reader lives. */
    fun watch() {
        core.scope.launch {
            var was: Boolean? = null
            network.online.collect { now ->
                if (!now) owed = false else if (was == false) returned()
                was = now
            }
        }
    }

    /** The reader is in front. */
    fun foreground() {
        front = true
        if (owed) askAgain()
    }

    /** The reader is no longer in front. */
    fun background() {
        front = false
    }

    private fun returned() {
        if (front) askAgain() else owed = true
    }

    private fun askAgain() {
        owed = false
        val state = core.current
        when (val phase = state.phase) {
            is ComicPhase.Failed -> if (phase.reason == AlbumUnreachable) opening.start()
            ComicPhase.Ready -> pagesAgain(state)
            ComicPhase.Loading -> Unit
        }
    }

    /**
     * The pages shown never wait; those of the spreads beside are fetched ahead again with the pages around,
     * as when the reader stays on a page.
     */
    private fun pagesAgain(state: ComicReaderUiState) {
        val pages = core.pages ?: return
        val spread = state.spread ?: return
        if (PageUnreachable !in state.pages.values) return
        spread.pages.filter { state.pages[it] == PageUnreachable }.forEach(pages::retry)
        pages.settle(spread.first)
    }
}
