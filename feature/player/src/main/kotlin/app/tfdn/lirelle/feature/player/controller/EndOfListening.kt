package app.tfdn.lirelle.feature.player.controller

import android.util.Log
import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.downloads.DeviceCopyControl
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.EndPanelActions
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Stars = 1..5
private const val Tag = "EndOfListening"

/**
 * The end panel of the book the session shows (spec 10): its stars, sent as the 2a panel's are when it
 * is closed (nothing without stars, nor for the stars already sent), then what closed it is done. It
 * lives with the controller, so that the panel left on the player is found again after "Rate" on the
 * mini-player. Call it on the main thread.
 *
 * Its state belongs to one book: a moment without a book (the service restarting) keeps it, another
 * book starts afresh, and so does the same book played again after its end (a new listening, a new
 * reading to rate). A listening is over when the book is played again after its end, closed from the
 * panel or followed by the next volume: its flusher baseline is cleared then, stars given or not.
 *
 * At the end, the reading the stars go to is looked for at once, and the stars it already has are shown
 * (the end reached again soon after the book was finished and rated, without a new reading): closing
 * the panel with them sends nothing, changing them rates that reading again.
 *
 * The panel's row for what the device keeps of the book is [device]'s, which follows the audiobook the session
 * shows.
 *
 * Without [ratings] (spec 2c) the panel has no stars, and the server is never asked for the reading they would go to.
 *
 * @param rating the rating logic of one listening of a book.
 */
internal class EndOfListening(
    private val send: (PlayerCommand) -> Unit,
    private val scope: CoroutineScope,
    val device: DeviceCopyControl,
    private val ratings: Boolean,
    private val rating: (bookId: BookId) -> ListeningRating,
) : EndPanelActions, DeviceCopyActions by device {
    private val mutableState = MutableStateFlow(EndPanelState())
    val state: StateFlow<EndPanelState> = mutableState.asStateFlow()

    private val mutableClosed =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The book was closed from the panel. */
    val closed: SharedFlow<Unit> = mutableClosed.asSharedFlow()

    private var bookId: BookId? = null
    private var rater: ListeningRating? = null
    private var finished = false

    /** The stars the server holds for this listening, null before any were sent. */
    private var sent: Int? = null

    /** The book the session shows, and whether it is finished. Null is a moment without a book: nothing changes. */
    fun onSession(id: BookId?, isFinished: Boolean) {
        if (id == null) return
        if (id != bookId) {
            bookId = id
            finished = isFinished
            listenAgain(id, beforeEnd = !isFinished)
            if (isFinished) atEnd()
            return
        }
        // Played again after its end: a new listening, whose end opens the panel again for its own reading.
        if (finished && !isFinished) {
            rater?.forgetBaseline()
            listenAgain(id, beforeEnd = true)
        }
        val reached = !finished && isFinished
        finished = isFinished
        if (reached) atEnd()
    }

    /** The end is reached: the stars of the reading they go to are shown, unless some were chosen meanwhile. */
    private fun atEnd() {
        if (!ratings) return
        val rater = rater ?: return
        scope.launch {
            val outcome = runCatching { rater.atEnd() }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val stars = outcome.getOrNull() ?: return@launch
            if (rater !== this@EndOfListening.rater) return@launch
            sent = stars
            mutableState.update { if (it.rating == null && !it.sending) it.copy(rating = stars) else it }
        }
    }

    override fun setRating(stars: Int?) {
        if (mutableState.value.sending) return
        mutableState.update { it.copy(rating = stars?.coerceIn(Stars), failed = false) }
    }

    override fun closeEndPanel() = finish {
        rater?.forgetBaseline()
        send(PlayerCommand.CloseBook)
        bookId = null
        rater = null
        finished = false
        mutableState.value = EndPanelState()
        mutableClosed.tryEmit(Unit)
    }

    override fun dismissEndPanel() = finish { mutableState.update { it.copy(panelSeen = true) } }

    override fun openEndPanel() = mutableState.update { it.copy(panelSeen = false) }

    override fun listenToNext() = finish {
        rater?.forgetBaseline()
        send(PlayerCommand.ListenToNext)
    }

    /** A listening of [id] seen from its beginning ([beforeEnd]), or from its end. */
    private fun listenAgain(id: BookId, beforeEnd: Boolean) {
        sent = null
        mutableState.value = EndPanelState()
        rater = rating(id).also { if (ratings) it.onOpened(beforeEnd) }
    }

    /** [then] once the stars are given: not while they are on their way, nor when they could not be. */
    private fun finish(then: () -> Unit) {
        val rater = rater ?: return
        val panel = mutableState.value
        if (!finished || panel.sending) return
        val stars = panel.rating
        if (stars == null || stars == sent) return then()
        mutableState.update { it.copy(sending = true, failed = false) }
        scope.launch {
            var done = false
            try {
                val outcome = runCatching { rater.rate(stars) }
                outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                outcome.exceptionOrNull()?.let { Log.w(Tag, "The stars could not be given", it) }
                done = outcome.getOrDefault(false)
            } finally {
                // Whatever happened, the panel is not left sending.
                if (rater === this@EndOfListening.rater) {
                    mutableState.update { it.copy(sending = false, failed = !done) }
                }
            }
            if (!done || rater !== this@EndOfListening.rater) return@launch
            sent = stars
            then()
        }
    }
}
