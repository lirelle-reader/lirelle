package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.LoadActions
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The books this app asked for, the last one kept: "Try again" after a load that failed asks it again,
 * as it was asked. A load the app did not ask for (a resumption after the process started, the
 * notification's next volume, a book whose files changed on the server) forgets the request: what
 * failed then is not known here, and nothing is offered ([retryable] false).
 *
 * @param nextVolume the next volume the session offers, which "Listen to the next one" loads.
 */
internal class LoadRequests(
    private val send: (PlayerCommand) -> Unit,
    private val nextVolume: () -> BookId?,
) : LoadActions {
    private var last: PlayerCommand.LoadBook? = null

    /** A request was sent and its load not seen yet. */
    private var awaiting = false
    private var wasLoading = false
    private var wasFailed = false

    private val mutableRetryable = MutableStateFlow(false)

    /** Whether there is a request to try again. */
    val retryable: StateFlow<Boolean> = mutableRetryable.asStateFlow()

    /**
     * [bookId] is asked for, from its position, or from its start with [fromStart]; to be played, or
     * left paused without [play].
     */
    fun asked(bookId: BookId, fromStart: Boolean, play: Boolean = true) {
        last = PlayerCommand.LoadBook(bookId, fromStart = fromStart, play = play)
        awaiting = true
        mutableRetryable.value = true
    }

    /** The book asked for whose load the session has not shown yet; null when none waits. */
    fun pending(): BookId? = last?.bookId?.takeIf { awaiting }

    /**
     * Nothing asked is asked again: when a narration is asked for (a narration is not a book to load again, and
     * its load is not this request's).
     */
    fun forget() {
        last = null
        awaiting = false
        mutableRetryable.value = false
    }

    /** Sends [command]; "Listen to the next one" is kept as the request of the next volume, as the service loads it. */
    fun forward(command: PlayerCommand) {
        if (command == PlayerCommand.ListenToNext) nextVolume()?.let { asked(it, fromStart = false) }
        send(command)
    }

    /** The session is [loading] a book, or that load [failed]: a load that starts unasked forgets the request. */
    fun onSession(loading: Boolean, failed: Boolean) {
        // A failure seen without its loading: the two came between two looks at the session.
        val started = (loading && !wasLoading) || (failed && !wasFailed && !wasLoading)
        wasLoading = loading
        wasFailed = failed
        if (!started) return
        if (awaiting) {
            awaiting = false
        } else {
            last = null
            mutableRetryable.value = false
        }
    }

    override fun retryLoad() {
        val request = last ?: return
        awaiting = true
        send(request)
    }
}
