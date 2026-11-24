package app.tfdn.lirelle.core.comics.fetch

import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.store.Album
import app.tfdn.lirelle.core.comics.store.ComicStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the reader, or a fetch that ended, tells the loader. They are handled one at a time, in their order. */
internal sealed interface Command {
    /** The reader arrived on the page, or asks again for one that failed. */
    data class Show(val index: Int) : Command

    data class Settle(val index: Int) : Command

    data class Jump(val index: Int) : Command

    /** Somebody looks at the page: if the device has it, it is told so. */
    data class Restore(val index: Int) : Command

    /**
     * The fetch [job] of the page ended, however it did.
     *
     * @property page what it found; null when it was given up, which says nothing of the page.
     */
    data class Done(val index: Int, val job: Job, val page: PageState?) : Command
}

/** What the device has of each page, for those who watch. */
internal class PageStates {
    private val flows = ConcurrentHashMap<Int, MutableStateFlow<PageState>>()

    fun of(index: Int): MutableStateFlow<PageState> = flows.getOrPut(index) { MutableStateFlow(PageState.Loading) }

    operator fun set(index: Int, state: PageState) {
        of(index).value = state
    }
}

/**
 * Decides which pages of an [album] are fetched and when, and starts their fetches. It is told one
 * [Command] at a time by a single coroutine, so nothing here is shared between threads but [states].
 *
 * @param commands where a fetch that ended says so.
 * @param pageCount the album's page count once it is known; before that the window has no end.
 */
internal class PageLoader(
    private val album: Album,
    private val source: AlbumSource,
    private val store: ComicStore,
    private val states: PageStates,
    private val commands: SendChannel<Command>,
    private val pageCount: () -> Int?,
) {
    private var queue = FetchQueue()
    private val jobs = mutableMapOf<Int, Job>()

    /** Fetches are started in [scope], and end with it. */
    fun handle(command: Command, scope: CoroutineScope) {
        when (command) {
            is Command.Show -> show(command.index, scope)
            is Command.Settle -> settle(command.index)
            is Command.Jump -> {
                jump(command.index)
                show(command.index, scope)
                settle(command.index)
            }
            is Command.Restore -> onDevice(command.index)
            is Command.Done -> if (jobs[command.index] === command.job) {
                jobs.remove(command.index)
                queue = queue.finished(command.index)
                // Told here, once the page is no longer on its way: who asks again on seeing it is heard.
                command.page?.let { states[command.index] = it }
            }
        }
        while (true) start(queue.next() ?: break, scope)
    }

    private fun show(index: Int, scope: CoroutineScope) {
        if (!onDevice(index)) queue.next(shown = index)?.let { start(it, scope) }
    }

    /** A page being fetched is left to its fetch: if that fails, it is the reader who asks again. */
    private fun settle(index: Int) {
        queue = queue.copy(waiting = window(index).filter { it !in queue.inFlight && !onDevice(it) })
    }

    /** A fetch dropped leaves its page as it was before it was asked for: not there. */
    private fun jump(index: Int) {
        val jumped = queue.jumped(window(index).toSet())
        jumped.dropped.forEach { jobs.remove(it)?.cancel() }
        queue = jumped.queue
    }

    private fun window(index: Int): List<Int> = windowOrder(index, pageCount() ?: Int.MAX_VALUE)

    /**
     * Whether the device has the page, which is then told to those who watch it. A page said ready whose
     * file went since (the system may clear the cache) is not there any more.
     */
    private fun onDevice(index: Int): Boolean {
        val file = album.page(index)
        if (index in queue.inFlight || !file.isFile) return false
        if (states.of(index).value is PageState.Ready) return true
        val size = imageSize(file) ?: return false
        states[index] = PageState.Ready(file, size.width, size.height)
        return true
    }

    private fun start(index: Int, scope: CoroutineScope) {
        queue = queue.started(index)
        states[index] = PageState.Loading
        val found = AtomicReference<PageState?>()
        val job = scope.launch {
            val page = source.page(album, index)
            if (page is PageState.Ready) store.trim()
            // A fetch given up says nothing: the page may be on its way again, and that fetch tells its state.
            ensureActive()
            found.set(page)
        }
        jobs[index] = job
        job.invokeOnCompletion { commands.trySend(Command.Done(index, job, found.get())) }
    }
}
