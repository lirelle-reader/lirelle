package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.feature.reader.bridge.BookFormat
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Gets the book from its id to the page, again after a failure, and gives everything back at the end.
 *
 * @param fromStart the book is read again: it opens at its beginning, not at its position.
 */
internal class Opening(
    private val core: ReaderCore,
    private val opener: BookOpener,
    private val sessions: ReadingSessionFactory,
    private val settingsLoaded: Deferred<Unit>,
    fromStart: Boolean,
) {
    /** Until the book was opened at its beginning once: opened again after a failure, it is where the user is. */
    private var startOver = fromStart
    private var job: Job? = null
    private var finished = false

    fun start() {
        job?.cancel()
        core.update { it.copy(phase = ReaderPhase.Downloading(null), opened = false) }
        job = core.scope.launch { opener.open(core.bookId).collect { step(it) } }
    }

    /** A page that failed is asked again with the book at hand; anything else starts from the beginning. */
    fun retry() {
        if (finished) return
        val book = core.book
        if (core.current.phase == ReaderPhase.Failed(ReaderFailure.Engine) && book != null) {
            show(book)
        } else {
            release()
            start()
        }
    }

    /** The `open` message, made when the page can take it: at the last known place, as it is drawn now. */
    fun openMessage(base: String): HostMessage.Open {
        val settings = core.pageSettings()
        core.sentSettings = settings
        return HostMessage.Open(
            base = base,
            cfi = core.lastCfi ?: core.restored?.cfi,
            fraction = core.current.location?.fraction ?: core.restored?.fraction,
            settings = settings,
            format = core.book?.content?.format ?: BookFormat.Epub,
        )
    }

    /** The reader is left: the reading is closed and the book given back. Only the first call does anything. */
    fun finish() {
        if (finished) return
        finished = true
        job?.cancel()
        core.driver.stop()
        release()
    }

    private suspend fun step(step: OpeningStep) = when (step) {
        is OpeningStep.Found -> core.update { it.copy(title = step.detail.title, coverUrl = step.detail.coverUrl) }
        is OpeningStep.Progress -> core.update { it.copy(phase = ReaderPhase.Downloading(step.progress)) }
        is OpeningStep.Failed -> core.fail(step.reason)
        is OpeningStep.Ready -> ready(step.book)
    }

    private suspend fun ready(book: OpenedBook) {
        core.book = book
        val session = sessions.create(book.file.id, core.scope)
        core.session = session
        core.restored = session.restore(fromStart = startOver)
        startOver = false
        settingsLoaded.await()
        show(book)
    }

    private fun show(book: OpenedBook) {
        core.update { it.copy(phase = ReaderPhase.Reading(book.content), opened = false) }
        core.driver.open()
    }

    /** The position first, then the session; the book is closed and its place in the cache freed. */
    private fun release() {
        core.session?.close()
        core.session = null
        core.book?.close()
        core.book = null
        core.restored = null
        core.lastCfi = null
        core.goBack.clear()
        core.update { it.copy(location = null, goBackShown = false) }
    }
}
