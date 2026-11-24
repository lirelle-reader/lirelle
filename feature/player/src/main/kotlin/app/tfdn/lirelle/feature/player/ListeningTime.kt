package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The time listened to a book, in seconds, as its statistics give it. */
internal data class Listened(val bookId: BookId, val seconds: Long)

/**
 * When the statistics are asked again after the end: the last session is declared once the final
 * position has left the device, and reaches the server a little later.
 */
private val AskAgainAfter = listOf(2.seconds, 5.seconds, 10.seconds, 20.seconds, 40.seconds)

/**
 * The end panel's listening time: the book's total time from its session statistics, the same figure
 * as on the book page. Asked as soon as the book reaches its end and shown at once; asked again a few
 * times until it grows past the first figure known, which is the last session arriving, and each time
 * the server took something the device sent ([changes]). Each end of the book starts afresh, so that
 * a listening again counts its new sessions; a book without statistics shows none. The figure only
 * grows.
 *
 * @param changes the version of what the server took from the device (`ReadingChanges.version`).
 */
internal class ListeningTime(
    private val library: LibraryRepository,
    private val changes: Flow<Long>,
    private val askAgainAfter: List<Duration> = AskAgainAfter,
) {
    private val mutableListened = MutableStateFlow<Listened?>(null)
    val listened: StateFlow<Listened?> = mutableListened.asStateFlow()

    /** The book whose end is being followed; null while no book is at its end. */
    private var followed: BookId? = null
    private var following: Job? = null
    private val asking = Mutex()

    /** What the player shows now; [scope] asks the server. */
    fun onState(state: PlayerUiState, scope: CoroutineScope) {
        val id = state.bookId?.takeIf { state.finished != null }
        if (id == followed) return
        followed = id
        following?.cancel()
        mutableListened.value = null
        if (id == null) return
        following = scope.launch {
            var first = ask(id)
            launch { changes.drop(1).collect { ask(id) } }
            for (wait in askAgainAfter) {
                delay(wait)
                val now = ask(id)
                // The first figure known is what the last session must add to.
                if (first == null) first = now else if (now != null && now > first) break
            }
        }
    }

    /** [state] with the time listened to it, while it is finished. */
    fun applyTo(state: PlayerUiState, listened: Listened?): PlayerUiState =
        if (state.finished != null && listened != null && listened.bookId == state.bookId) {
            state.copy(listenedSeconds = listened.seconds)
        } else {
            state
        }

    /**
     * The statistics of [id], one question at a time; a total smaller than the one shown is an older
     * answer and is not shown. Returns the total shown, null while none is.
     */
    private suspend fun ask(id: BookId): Long? = asking.withLock {
        val total = (library.bookReading(id) as? ApiResult.Success)?.value?.totalSeconds?.takeIf { it > 0 }
        val shown = mutableListened.value?.takeIf { it.bookId == id }?.seconds
        val larger = total != null && (shown == null || total > shown)
        if (followed == id && larger) mutableListened.value = Listened(id, total)
        mutableListened.value?.takeIf { it.bookId == id }?.seconds
    }
}
