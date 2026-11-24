package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageErrorCode
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.narration.NarrationPart

/** The share of the width, on each side, where a tap turns the page. */
private const val EdgeZone = 0.2

/** The top-right corner where a tap toggles the bookmark, in shares of the width and of the height. */
private const val CornerWidth = 0.15
private const val CornerHeight = 0.1

/** What the page says, turned into state, positions and commands. */
internal class PageEvents(
    private val core: ReaderCore,
    private val appearance: Appearance,
    private val opening: Opening,
    private val endOfBook: EndOfBook,
    private val marks: MarksPart,
    private val search: SearchPart,
    private val notes: NotesPart,
    private val narration: NarrationPart,
) : PageDriver.Listener {

    /** The last relocation since the page opened the book: the page sends it again when only its bookmarks change. */
    private var lastRelocation: PageMessage.Relocated? = null

    override suspend fun onMessage(message: PageMessage) = when (message) {
        PageMessage.Ready -> Unit
        is PageMessage.Opened -> opened(message)
        is PageMessage.Relocated -> relocated(message)
        PageMessage.PastEnd -> endOfBook.onPastEnd()
        PageMessage.Turning -> turning()
        is PageMessage.Tap -> tap(message)
        is PageMessage.Brightness -> appearance.onBrightness(message)
        is PageMessage.Link -> link(message)
        is PageMessage.Error -> error(message)
        is PageMessage.Marking -> marks.onMessage(message)
        is PageMessage.Finding -> search.run.onMessage(message)
        is PageMessage.Noting -> notes.onMessage(message)
        is PageMessage.Narrating -> narration.onMessage(message)
    }

    override fun onStuck() = core.fail(ReaderFailure.Engine)

    override fun openMessage(base: String): HostMessage.Open = opening.openMessage(base)

    private fun opened(message: PageMessage.Opened) {
        if (!core.driver.opened) return
        lastRelocation = null
        core.movesAwaited = 0
        core.update { it.copy(toc = message.toc, rtl = message.dir == PageDirection.Rtl, opened = true) }
        // What changed while the book was opening.
        core.pushSettings()
        marks.onOpened(message.sectionLabels)
        notes.onClosed()
        search.run.onOpened()
        endOfBook.onOpened()
        narration.starts.forget()
        narration.following.onOpened()
    }

    /**
     * A relocation the reader did not ask for, and the page says the user made
     * ([PageMessage.Relocated.userMove]), is a page turned by the user, with a swipe, a tap or a key:
     * it hides the controls, counts towards forgetting "Go back" and stops following the narration.
     * A layout again after a resize is nobody's. One the page made to follow the narration
     * ([PageMessage.Relocated.byNarration]) is not the user's either: see [followed].
     *
     * While "Go back" is offered the user is looking elsewhere: the place is shown, but it is not
     * the reading position until they read on from it, or leave. Coming back sends nothing.
     *
     * The same place sent again with other bookmarks only says which bookmarks the page holds: it
     * is neither a move nor the answer to one, which may still be on its way. Any relocation brings
     * back the ribbon a turn took away, the same place sent again after a turn that could not happen
     * included.
     *
     * A relocation [PageMessage.Relocated.byHost] answers a jump, even to the place already shown:
     * never a page turned, never a resend. While later jumps are still to be answered, the page is
     * only passing through: the place is shown, nothing else. Only the answer to the last jump ends
     * the wait, and brings the user back after "Go back".
     */
    private suspend fun relocated(message: PageMessage.Relocated) {
        val resent = message.resends(lastRelocation, core.lastCfi)
        lastRelocation = message
        core.update { it.copy(pageBookmarks = message.bookmarkIds.map(::MarkId), ribbonHidden = false) }
        if (resent) return
        core.sectionIndex = message.sectionIndex
        if (core.isFollow(message)) return followed(message)
        val passing = message.byHost && core.movesAwaited > 1
        // A page from before the answers never sends one: what it sends ends the wait, as it always did.
        core.movesAwaited = if (message.byHost) (core.movesAwaited - 1).coerceAtLeast(0) else 0
        if (passing) {
            core.lastCfi = message.cfi
            core.update { it.copy(location = message.location()) }
            return
        }
        val turned = message.turnsFrom(core.lastCfi) && !core.expected
        val returned = core.returning && message.moved
        core.expected = false
        core.returning = false
        core.lastCfi = message.cfi
        if (turned) {
            core.goBack.onPageTurn()
            core.onNavigated()
        }
        core.update {
            it.copy(
                location = message.location(),
                goBackShown = !core.goBack.isEmpty,
                controlsShown = it.controlsShown && !turned,
            )
        }
        endOfBook.onPosition(message.progress)
        if (returned) {
            core.session?.returned(message.cfi, message.progress)
        } else {
            core.session?.relocated(message.cfi, message.progress, message.moved, peek = !core.goBack.isEmpty)
        }
        narration.starts.onRelocated(message.moved)
    }

    /**
     * The page went along with the narration: the place is shown, but it is neither a page the user turned nor
     * the answer to a jump, and not a text position (the narration's own position moves the text on the server).
     */
    private suspend fun followed(message: PageMessage.Relocated) {
        if (message.byHost) core.movesAwaited = (core.movesAwaited - 1).coerceAtLeast(0)
        core.lastCfi = message.cfi
        core.update { it.copy(location = message.location()) }
        endOfBook.onPosition(message.progress)
        core.session?.relocated(message.cfi, message.progress, moved = false, byUser = false)
    }

    /** A page turn of the user's is coming: the ribbon of the page left goes at once. */
    private fun turning() {
        core.hideRibbon()
        core.onNavigated()
    }

    /**
     * The top-right corner toggles the bookmark, when the setting is on and the source has annotations,
     * whatever the book's direction. The centre shows or hides the controls; the sides turn the page, forward on the
     * side the book reads towards.
     */
    private fun tap(message: PageMessage.Tap) {
        val corner = core.current.settings.cornerBookmark && core.capabilities.annotations
        if (message.x > 1 - CornerWidth && message.y < CornerHeight && corner) {
            marks.bookmarks.toggleBookmark()
            return
        }
        val left = message.x < EdgeZone
        val right = message.x > 1 - EdgeZone
        when {
            !left && !right -> core.update { it.copy(controlsShown = !it.controlsShown) }
            else -> core.turn(forward = right != core.current.rtl)
        }
    }

    /** The page has already followed a link of the book; one that leads outside waits for the user's word. */
    private fun link(message: PageMessage.Link) {
        if (message.external) {
            webAddress(message.href)?.let { address -> core.update { it.copy(externalLink = address) } }
            return
        }
        core.expected = true
        core.onNavigated()
        core.lastCfi?.let(core.goBack::push)
        core.update { it.copy(goBackShown = !core.goBack.isEmpty, noteOpen = false) }
    }

    /** A section that fails once the book is shown is the page's business: it says so in place. */
    private fun error(message: PageMessage.Error) = when (message.code) {
        PageErrorCode.UnsupportedEngine -> core.fail(ReaderFailure.OldWebView)
        PageErrorCode.OpenFailed -> core.fail(ReaderFailure.Engine)
        PageErrorCode.CannotOpen -> core.fail(ReaderFailure.CannotOpen)
        PageErrorCode.SectionFailed -> Unit
    }
}

/**
 * How far into the book, from 0 to 1. The last page is the whole book, whatever the page counted:
 * the server only takes a book as read from a position at its end.
 */
private val PageMessage.Relocated.progress: Double get() = if (atEnd) 1.0 else fraction

/** The same place as [last] sent again by the page on its own, at [lastCfi], with other bookmarks only. */
private fun PageMessage.Relocated.resends(last: PageMessage.Relocated?, lastCfi: String?): Boolean =
    !byHost && cfi == lastCfi && last?.copy(bookmarkIds = bookmarkIds, byHost = false, userMove = userMove) == this

/**
 * A page the user turned, away from [lastCfi]: their own move, not the answer to the host's. A layout again (a
 * resize, the narration's bar coming) lands elsewhere too, but is nobody's move.
 */
private fun PageMessage.Relocated.turnsFrom(lastCfi: String?): Boolean =
    moved && userMove && !byHost && cfi != lastCfi

private fun PageMessage.Relocated.location() = Location(
    chapter = tocLabel,
    tocId = tocId,
    fraction = progress,
    page = page,
    pages = pages,
    atEnd = atEnd,
)
