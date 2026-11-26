package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Starts a book the car asked for: what to play of it and from where is asked of the car's rules
 * ([requestFor]), then it is loaded and played exactly as the app's own commands load it. The session shows the
 * book as starting meanwhile, so the service plays without the app's screens. A book asked while another is
 * still looked up, or still loaded, takes its place: the one asked last is the one that plays, or none.
 *
 * @param requestFor null when the book has nothing to listen to.
 * @param failed nothing could be started: the car is told.
 */
class CarBookStart(
    private val session: BookSession,
    private val scope: CoroutineScope,
    private val requestFor: suspend (BookId) -> LoadRequest?,
    private val failed: () -> Unit,
) : ChapterPlayer.Starts {

    /** The book being looked up: a car that asks for another one meanwhile gets that one, whichever answers first. */
    private var asking: Job? = null

    /** The load of the book found last: given up too, lest it open and play while the next one is looked up. */
    private var loading: Job? = null

    override fun start(bookId: BookId, play: () -> Boolean, done: () -> Unit) {
        // Said once, whichever way the start ends.
        var told = false
        val end = {
            if (!told) {
                told = true
                done()
            }
        }
        asking?.cancel()
        loading?.cancel()
        asking = scope.launch {
            // Rules that cannot be read (a store, the server) start nothing: done always comes.
            val request = runCatching { requestFor(bookId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (request == null) {
                end()
                failed()
                return@launch
            }
            session.load(request, play = true, playing = play).also { loading = it }.invokeOnCompletion { cause ->
                end()
                if (cause == null && session.extras.value.loadFailed) failed()
            }
        }.also { asked ->
            // Given up for another book, or the service ended, before anything was loaded: no failure to say.
            asked.invokeOnCompletion { cause -> if (cause != null) end() }
        }
    }
}
