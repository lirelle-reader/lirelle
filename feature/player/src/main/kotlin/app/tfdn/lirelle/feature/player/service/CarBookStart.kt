package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starts a book the car asked for: what to play of it and from where is asked of the car's rules
 * ([requestFor]), then it is loaded and played exactly as the app's own commands load it. The session shows the
 * book as starting meanwhile, so the service plays without the app's screens.
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

    override fun start(bookId: BookId, play: () -> Boolean, done: () -> Unit) {
        scope.launch {
            // Rules that cannot be read (a store, the server) start nothing: done always comes.
            val request = runCatching { requestFor(bookId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (request == null) {
                done()
                failed()
                return@launch
            }
            session.load(request, play = true, playing = play).invokeOnCompletion { cause ->
                done()
                if (cause == null && session.extras.value.loadFailed) failed()
            }
        }
    }
}
