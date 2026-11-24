package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.reader.sync.RestoredPosition
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.bridge.PageNoteLabels
import app.tfdn.lirelle.feature.reader.bridge.PageSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the parts of the reader share: the state shown, the book and its reading, the page, and what the
 * profile's source can do ([capabilities], fixed for the reader's life). Everything here is touched from the
 * main thread only.
 */
internal class ReaderCore(
    val bookId: BookId,
    val scope: CoroutineScope,
    val capabilities: SourceCapabilities = SourceCapabilities.Everything,
) {
    val state = MutableStateFlow(ReaderUiState(capabilities = capabilities))
    val events = Channel<ReaderEvent>(Channel.UNLIMITED)
    val driver = PageDriver(scope)
    val goBack = GoBackStack()

    var book: OpenedBook? = null
    var session: ReadingSession? = null

    /** Where the book opens, until the page has said where it is. */
    var restored: RestoredPosition? = null

    /** Where the page last said it was. */
    var lastCfi: String? = null

    /** The section of the spine the page last said it shows, the first one of a spread. */
    var sectionIndex: Int? = null

    /** Told of each move the user makes: a page turned, a jump to somewhere else. */
    var onNavigated: () -> Unit = {}

    /** Told when the reader is in front again. */
    var onFront: () -> Unit = {}

    /** The position of the server shown in the banner. */
    var offered: ReadingPosition? = null

    /** The next relocation answers a jump or a change of layout: it is not a page turned. */
    var expected = false

    /** The jumps sent and not answered yet, by a page that answers each one (`byHost`). */
    var movesAwaited = 0

    /** Of [movesAwaited], the moves made to follow the narration ([follow]): their answers are the narration's. */
    private var followMoves = 0

    /** The next relocation answers the last "Go back": the user is where they were before looking elsewhere. */
    var returning = false
    var systemDark = false
    var wide = false

    /** The note panel's texts in the app's language, as the screen gave them. */
    var noteLabels: PageNoteLabels = EnglishNoteLabels

    /** The texts made up for marks in the app's language, as the screen gave them. */
    var markLabels: MarkLabels = EnglishMarkLabels

    /** What the page was last told to draw with. */
    var sentSettings: PageSettings? = null

    val current: ReaderUiState get() = state.value

    /** The file read, in which marks are placed; null until the book is at hand. */
    val fileId: FileId? get() = book?.file?.id

    fun update(transform: (ReaderUiState) -> ReaderUiState) = state.update(transform)

    fun pageSettings(): PageSettings =
        current.settings.toPageSettings(current.colours, twoPages = wide, noteLabels = noteLabels)

    /** Tells the page how to draw, when that changed and the page shows the book. */
    fun pushSettings() {
        val settings = pageSettings()
        val before = sentSettings
        if (settings == before || !driver.send(HostMessage.ApplySettings(settings))) return
        sentSettings = settings
        // Only a new layout makes the page relocate; the next relocation then is not a page turned.
        if (before == null || settings.layoutDiffers(before)) expected = true
    }

    /**
     * Moves somewhere else in the book, for the user; with [remember], "Go back" returns to where the user
     * is now. The page answers with a relocation, sent even to the place it already shows.
     */
    fun jump(target: HostMessage.Move, remember: Boolean) {
        if (!driver.send(target)) return
        onNavigated()
        followMoves = 0
        movesAwaited++
        expected = true
        returning = false
        if (remember) lastCfi?.let(goBack::push)
        update { it.copy(goBackShown = !goBack.isEmpty) }
    }

    /**
     * Moves to follow the narration where the page cannot by itself: not a move of the user's, and its answer is
     * the narration's as a followed move is ([isFollow]).
     */
    fun follow(target: HostMessage.Move) {
        if (!driver.send(target)) return
        movesAwaited++
        followMoves++
    }

    /**
     * Whether [message] shows the page following the narration: moved by the page itself, or the answer to a
     * [follow] while no other jump waits for its own.
     */
    fun isFollow(message: PageMessage.Relocated): Boolean {
        if (message.byNarration) return true
        // A page opened again waits for nothing.
        followMoves = followMoves.coerceAtMost(movesAwaited)
        if (!message.byHost || followMoves == 0 || movesAwaited > followMoves) return false
        followMoves--
        return true
    }

    /**
     * Turns one page, which hides the controls. The ribbon is the page's to hide: it says when a
     * turn is coming, which it does not in scrolled layout, nor when there is nothing to turn to.
     */
    fun turn(forward: Boolean) {
        if (driver.send(if (forward) HostMessage.Next else HostMessage.Prev)) {
            onNavigated()
            update { it.copy(controlsShown = false) }
        }
    }

    /**
     * A page turn is about to happen: the ribbon of the page left is hidden at once, not with the
     * turn's relocation, which comes once the new page is drawn and says the bookmarks it holds. The
     * bookmarks themselves are kept: the button still removes those of the page on screen.
     */
    fun hideRibbon() = update { it.copy(ribbonHidden = true) }

    /** The book cannot be shown. */
    fun fail(reason: ReaderFailure) {
        driver.stop()
        update { it.copy(phase = ReaderPhase.Failed(reason), opened = false) }
    }
}
