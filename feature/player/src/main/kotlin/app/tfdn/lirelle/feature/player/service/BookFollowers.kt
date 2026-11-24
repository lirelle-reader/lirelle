package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.narration.NarrationPositionWriter
import app.tfdn.lirelle.core.audio.position.PositionWriter
import app.tfdn.lirelle.core.ids.BookId
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where the positions of [book] go: an audiobook's playback states to [playback], a narration's places to
 * [narration].
 */
fun sinkOf(book: LoadedBook, playback: PositionWriter, narration: NarrationPositionWriter, wall: Clock): PositionSink =
    when (val source = book.source) {
        BookSource.Audiobook -> PlaybackSink(playback, book.playlist, wall)
        is BookSource.Narration -> NarrationSink(narration, source.fileId, source.map, wall)
    }

/**
 * Keeps the book a play button resumes after the service ended ([LastBookStore]): the audiobook opened, until
 * the user closes it. A narration is never one (it plays with its text): opening one forgets the last audiobook,
 * so that a play button never starts an older, other book than the one last heard; closing it then changes
 * nothing.
 *
 * There are two closings. The user stops listening ("Stop listening", the notification dismissed, the end
 * panel's "Close"): the book is forgotten, so that a play button does not resume what was stopped. The profile
 * closes ([profileCloses]): the book is closed all the same, but stays the profile's last book, which a play
 * button resumes when the profile is the current one again.
 *
 * @param lasting for what must be written even as the service ends.
 */
class LastBookKeeper(private val store: LastBookStore, private val lasting: CoroutineScope) {
    private var narrating = false

    /** The profile closes: the closing that follows is not the user's, and forgets nothing. */
    private var profileClosed = false

    fun opened(book: LoadedBook) {
        narrating = book.source is BookSource.Narration
        if (narrating) {
            lasting.launch { store.clear() }
            return
        }
        val last = LastBook(book.bookId, book.playlist.manifest.title, book.playlist.artworkUrl)
        lasting.launch { store.put(last) }
    }

    /**
     * Said by the service before it closes the book of a profile that closes: from then on the last book is
     * kept as it is. Never taken back: the service plays for no other profile, and ends.
     */
    fun profileCloses() {
        profileClosed = true
    }

    fun closed() {
        if (!narrating && !profileClosed) lasting.launch { store.clear() }
        narrating = false
    }
}

/**
 * What follows the book loaded in the service, wired to its [BookSession]: its positions and its listening
 * leave with it, each book's positions go to their own writer, the narration
 * ticker follows it, the session activity opens what suits it (the reader for a narration), and the last
 * audiobook is kept for a play button after the service ended, until the user closes it — not when its profile
 * closes ([LastBookKeeper]). A book played from its
 * download is held while it is loaded, so that it is not deleted ([DownloadHold]).
 *
 * @param sessionActivity sets what a tap on the notification opens for the book (none: the app).
 * @param lasting for what must be written even as the service ends.
 */
class BookFollowers(
    private val parts: PlaybackParts,
    private val loop: PlaybackSessionLoop,
    private val watcher: NetworkWatcher,
    private val ticker: NarrationTicker,
    private val sessionActivity: (LoadedBook?) -> Unit,
    lasting: CoroutineScope,
) {
    private val lastBook = LastBookKeeper(parts.lastBooks, lasting)
    private val download = DownloadHold(parts.sources.kept, lasting)

    fun attach(book: BookSession) {
        book.narrations = NarrationParts(parts.narration.loader, parts.narration.cursor)
        book.leaving = {
            loop.leave()
            watcher.reset()
        }
        // The next listening of the finished book captures its own baseline.
        book.finishedLeft = parts.sources.baselines::clear
        book.opened = ::opened
        book.closed = {
            watcher.reset()
            ticker.follow(null)
            sessionActivity(null)
            lastBook.closed()
            download.closed()
        }
        book.resumable = { parts.lastBooks.get()?.bookId }
    }

    private fun opened(book: LoadedBook) {
        val sink = sinkOf(book, parts.listening.writer, parts.narration.writer, parts.clocks.wall)
        loop.positions = BookPositions(book.playlist, sink)
        ticker.follow(book)
        sessionActivity(book)
        lastBook.opened(book)
        download.opened(book)
    }

    /** The profile closes: the book closed next stays its last book ([LastBookKeeper.profileCloses]). */
    fun profileCloses() = lastBook.profileCloses()

    /** The service ends: the book it held is let go. */
    fun release() = download.closed()

    /** Whether the book loaded is [bookId], played from its download: it is not loaded again for the server's files. */
    fun playsKept(bookId: BookId): Boolean = download.holds(bookId)
}
