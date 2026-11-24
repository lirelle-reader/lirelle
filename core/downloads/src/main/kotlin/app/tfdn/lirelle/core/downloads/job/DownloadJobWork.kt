package app.tfdn.lirelle.core.downloads.job

import app.tfdn.lirelle.core.downloads.engine.DownloadRunner
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * What the system's job does, apart from being a service.
 *
 * @param waitsForSignIn whether the profile's session waits for a sign-in, asked when a run ends.
 */
class DownloadJobWork internal constructor(
    private val runUntilIdle: suspend () -> Unit,
    books: Flow<List<DownloadedBook>>,
    private val waitsForSignIn: () -> Boolean,
) {
    @Inject
    constructor(runner: DownloadRunner, registry: DownloadRegistry, network: ProfileNetwork) :
        this(runner::runUntilIdle, registry.books, { network.waitsForSignIn.value })

    /** Whether something is still to fetch: what failed is not. */
    val left: Flow<Boolean> = books.map { jobRequest(it, allowMetered = false) != null }.distinctUntilChanged()

    /**
     * Fetches until nothing can be, or until its profile closes ([open] turns false), which stops the fetching.
     * True when the job is to be run again: downloads are left, waiting for a network they may use. Never for
     * a profile that closed: nothing is fetched for it any more, and the next one asks for its own job. Nor
     * for one whose session waits for a sign-in: the network it waits for is not one the system knows of —
     * the device has one, so the job would be run again, to fetch nothing, for as long as nobody signs in.
     * The job is asked for again when somebody does ([DownloadJobScheduler]).
     *
     * The runner is the profile's, but runs here in the scope of whoever calls, which the profile's end does
     * not cancel: left to itself it would go on with the stores of a profile that closed.
     */
    suspend fun run(open: Flow<Boolean>): Boolean = channelFlow {
        launch { open.filterNot { it }.take(1).collect { send(false) } }
        launch {
            runUntilIdle()
            send(left.first() && !waitsForSignIn())
        }
    }.first()
}
