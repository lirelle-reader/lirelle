package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.feature.reader.bridge.PageNoteLabels
import app.tfdn.lirelle.feature.reader.bridge.ReaderEngine
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationActions

/** Something to do once, outside the reader's own state. */
sealed interface ReaderEvent {
    /** Leave the reader for the screen it was opened from. */
    data object Leave : ReaderEvent

    /** Leave the reader and show the page of this book. */
    data class OpenBook(val bookId: BookId) : ReaderEvent

    /** Open this web address, which the user confirmed, in a browser. It is `http` or `https`. */
    data class OpenUrl(val url: String) : ReaderEvent
}

/** Moving in the book. Nothing happens while the book is not on screen. */
interface ReaderNavigation {
    fun nextChapter()

    fun previousChapter()

    /** One page forward in the book, whatever its direction, as a tap on the side does. It hides the controls. */
    fun nextPage()

    /** One page back in the book. */
    fun previousPage()

    /** A jump: "Go back" is offered. It closes the open sheet. */
    fun goToToc(entry: TocEntry)

    /** A jump to [fraction] of the book, from 0 to 1: "Go back" is offered. */
    fun seekTo(fraction: Double)

    /** Returns to where the last jump was made from. */
    fun goBack()
}

/** The reader's own bars and sheets. */
interface ReaderChrome {
    fun toggleControls()

    fun showSheet(sheet: ReaderSheet)

    fun dismissSheet()

    /** Stored, and applied to the page at once. */
    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings)

    /**
     * The brightness of the reader's own, from 0 to 1, while a finger moves it: on screen at once,
     * and stored by [keepBrightness] only, or at the latest when the reader is left.
     */
    fun setBrightness(level: Float)

    /** The finger is lifted: the brightness last given to [setBrightness] is stored. */
    fun keepBrightness()

    /** After a failure: tries again what failed. */
    fun retry()

    /** The back arrow, and "Cancel" during a download: sends [ReaderEvent.Leave]. */
    fun leave()
}

/** Answers to what the reader asks the user. */
interface ReaderPrompts {
    /** "Go there": moves to the position offered. "Go back" is offered. */
    fun acceptNewerPosition()

    fun ignoreNewerPosition()

    /** Sends [ReaderEvent.OpenUrl]. */
    fun confirmExternalLink()

    fun dismissExternalLink()
}

/** The panel at the end of the book, with its row for what the device keeps of the book ([DeviceCopyActions]). */
interface EndPanelActions : DeviceCopyActions {
    /** From 1 to 5, or null for no star. Nothing is sent before the panel is closed. */
    fun setRating(stars: Int?)

    /**
     * "Close the book": sends the rating, if any, then closes the panel and sends [ReaderEvent.Leave].
     * A failure keeps the panel open and the reader where it is, and this tries again.
     */
    fun closeEndPanel()

    /** As [closeEndPanel], but sends [ReaderEvent.OpenBook] for the next volume instead. */
    fun openNextVolume()

    /**
     * A swipe down on the panel or a tap above it, as the system's back: sends the rating, if any, then
     * closes the panel only; the reader stays on the last page.
     */
    fun dismissEndPanel()
}

/** Everything the user can do in the reader. */
interface ReaderActions :
    ReaderNavigation,
    ReaderChrome,
    ReaderPrompts,
    EndPanelActions,
    MarksActions,
    SearchActions,
    ReaderNarrationActions

/** What the screen and its window tell the reader. Call these on the main thread. */
interface ReaderHostEvents {
    /** The web view's engine; again, with a new one, each time the web view had to be replaced. */
    fun onEngine(engine: ReaderEngine)

    /** The reader is in front (again). */
    fun onResumed()

    /** The reader is no longer in front. */
    fun onPaused()

    /** The reader is being left for good: the reading is closed. Also done when the view model is cleared. */
    fun onLeave()

    /**
     * The system's back: whatever is drawn on top closes first. The question about a web link, then
     * the footnote, then the highlight's panel (asking about a changed note first), then the
     * selection, a sheet, the panel at the end of the book, the search's bar; then it goes back from
     * a jump, then hides the controls.
     * False when there was nothing of the kind: the caller leaves the reader.
     */
    fun onBack(): Boolean

    fun onSystemDark(dark: Boolean)

    /** [wide]: the window is at the expanded width and in landscape, where two pages fit side by side. */
    fun onWindow(wide: Boolean)

    /** The texts of the note panel, in the language of the app; sent to the page with the settings. */
    fun onNoteLabels(labels: PageNoteLabels) = Unit

    /** The texts the reader makes up for marks, in the language of the app. */
    fun onMarkLabels(labels: MarkLabels) = Unit

    /** The window's brightness when the reader does not set it, from 0 to 1: where a first drag starts from. */
    fun onSystemBrightness(level: Float)

    /**
     * A volume key was pressed: [volumeDown] turns forward, the other one back. False when the key
     * is not the reader's (option off, book not on screen, something open above the page).
     */
    fun onVolumeKey(volumeDown: Boolean): Boolean
}
