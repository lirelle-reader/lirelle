package app.tfdn.lirelle

import app.tfdn.lirelle.core.audio.bookmarks.BookmarkFlusher
import app.tfdn.lirelle.core.audio.position.PlaybackFlusher
import app.tfdn.lirelle.core.reader.marks.MarksFlusher
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import javax.inject.Inject

/**
 * Has the reading and listening that wait on the device sent whenever sending may work again: when the
 * profile opens — the application starts, or somebody signs in — and when the profile's network comes back:
 * the device's, or the session itself, when the account signs in again after the server stopped renewing it.
 * The flushers are the profile's own: each gives up for a while after a failure, and ends with the profile.
 *
 * @param kicks each asks one flusher (reading progress, marks, listening positions, audiobook bookmarks) for a flush;
 *   asking during a flush does not start a second one.
 */
class OutboxKicker(private vararg val kicks: () -> Unit) {

    @Inject
    constructor(
        flusher: OutboxFlusher,
        marks: MarksFlusher,
        listening: PlaybackFlusher,
        bookmarks: BookmarkFlusher,
    ) : this(flusher::kick, marks::kick, listening::kick, bookmarks::kick)

    private fun kick() = kicks.forEach { it() }

    /**
     * The profile opens: what waits may go now. For a profile that opens with a session waiting for a sign-in
     * the attempt fails on the device, as without a network, and what waits stays.
     */
    fun start() = kick()

    /** The profile has a network again: what could not be sent without one may go now. */
    fun onNetworkAvailable() = kick()
}
