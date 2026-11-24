package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageErrorCode
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.bridge.ReaderEngine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How long a book may take to appear. The page gives up by itself after 16 seconds and says so. */
val OpeningTimeout: Duration = 20.seconds

/**
 * How many times in a row the web view may be replaced, its renderer dead, before the book it was
 * showing or opening is given up: a book that kills the renderer would otherwise be reopened at
 * the same place for ever.
 */
private const val MaxRendererDeaths = 2

/**
 * Talks to the page of the current web view. The page is told to open the book once it has said
 * it is ready and the web view serves the book; nothing is sent to it before it is ready, and
 * nothing but `open` before the book is shown.
 */
internal class PageDriver(private val scope: CoroutineScope) {

    interface Listener {
        /** Every message of the page, in order. */
        suspend fun onMessage(message: PageMessage)

        /**
         * The book did not appear within [OpeningTimeout], or the web view died twice in a row
         * without showing it in between.
         */
        fun onStuck()

        /** What to open, at the moment the page can take it. [base] is where the book is served. */
        fun openMessage(base: String): HostMessage.Open
    }

    lateinit var listener: Listener

    private var engine: ReaderEngine? = null
    private var listening: Job? = null
    private var opening: Job? = null
    private var watchdog: Job? = null
    private var ready = false
    private var wanted = false

    /** Web views replaced under a wanted book since it was last shown, or last asked for. */
    private var deaths = 0

    /** What a page that cannot run the engine said, kept to be said again when a book is to be opened. */
    private var unsupported: PageMessage.Error? = null

    /** The page shows the book. */
    var opened = false
        private set

    /** A new web view, at the start or after the one before died: its page starts afresh. */
    fun attach(engine: ReaderEngine) {
        listening?.cancel()
        opening?.cancel()
        if (wanted && this.engine != null) deaths++
        this.engine = engine
        ready = false
        opened = false
        unsupported = null
        listening = scope.launch { engine.messages.collect { received(it) } }
        when {
            !wanted -> Unit
            deaths >= MaxRendererDeaths -> listener.onStuck()
            else -> watch()
        }
    }

    /** There is a book to show, now or as soon as the page can. */
    fun open() {
        wanted = true
        opened = false
        deaths = 0
        val refusal = unsupported
        if (refusal != null) {
            scope.launch { listener.onMessage(refusal) }
            return
        }
        watch()
        tryOpen()
    }

    /** There is no book to show any more. */
    fun stop() {
        wanted = false
        opened = false
        opening?.cancel()
        watchdog?.cancel()
    }

    /** False, and nothing sent, while the page does not show the book. */
    fun send(message: HostMessage): Boolean {
        val page = engine?.takeIf { opened } ?: return false
        page.send(message)
        return true
    }

    private suspend fun received(message: PageMessage) {
        when (message) {
            PageMessage.Ready -> {
                ready = true
                tryOpen()
            }
            is PageMessage.Opened -> {
                opened = wanted
                if (wanted) deaths = 0
                watchdog?.cancel()
            }
            is PageMessage.Error -> if (message.code == PageErrorCode.UnsupportedEngine) unsupported = message
            else -> Unit
        }
        listener.onMessage(message)
    }

    private fun tryOpen() {
        val page = engine?.takeIf { wanted && ready } ?: return
        opening?.cancel()
        opening = scope.launch {
            val base = page.bookBase.filterNotNull().first()
            page.send(listener.openMessage(base))
        }
    }

    private fun watch() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(OpeningTimeout)
            listener.onStuck()
        }
    }
}
