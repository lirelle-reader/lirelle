package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.downloads.DeviceCopyActions

/** Moving in the album. Pages are indexes from 0. */
interface ComicNavigation {
    /**
     * To the next spread in reading order; on the last one, no move: the panel at the end opens. Asked from
     * the page, as [previous] is: the bars hide.
     */
    fun next()

    /** To the spread before; nothing on the first one. */
    fun previous()

    /**
     * The slider was released on [pageIndex]: the reader goes to the spread that shows it. Read vertically, the
     * strip goes to the top of that page, the page read too.
     */
    fun goTo(pageIndex: Int)

    /** The slider is dragged over [pageIndex]; null when it no longer is, without having gone anywhere. */
    fun previewPage(pageIndex: Int?)

    /** Asks again for a page that could not be loaded. */
    fun retryPage(index: Int)

    /**
     * The zoom rests on the page [index], whose picture would need [longSidePx] pixels on its long side to be
     * sharp there: a page drawn on the device is drawn again that large.
     */
    fun sharpenPage(index: Int, longSidePx: Int)
}

/** What the strip of an album read vertically tells the reader as it scrolls. Pages are indexes from 0. */
interface StripNavigation {
    /**
     * The strip shows the pages from [first] to [last]; [first], at the top of the screen, is the page read. With
     * [atEnd] the strip is at its very bottom: the page read is [last], the last one of the album, however little
     * of the screen it takes, and the pages above it are still those shown.
     *
     * Without [scrolled] the user has not moved the strip since the album opened, or since the reader last landed
     * on a page: the page read stays the one opened or landed on while the strip shows it, even where a strip
     * held at its bottom has it lower than the top of the screen. Opening an album near its end does not read it
     * to its end.
     */
    fun stripShows(first: Int, last: Int, atEnd: Boolean, scrolled: Boolean)

    /** The strip came to rest: where the reader is is written, and the pages around are fetched ahead. */
    fun stripRested()

    /** The user went on past the bottom of the last page: the panel at the end opens. */
    fun stripPastEnd()
}

/** The choices of the settings sheet. */
interface ComicSettingsActions {
    fun setFit(fit: Fit)

    fun setTwoPages(twoPages: TwoPages)

    fun setBackground(background: Background)

    /** For the series of the album, or for the album alone when it has none. */
    fun setDirection(direction: Direction)

    /** Whether, panel by panel, each page is shown whole before its first panel: the device's, as the fit is. */
    fun setWholePageFirst(first: Boolean)
}

/** Reading panel by panel, and what a build that can be debugged offers of it besides. */
interface PanelModeActions {
    /**
     * Turns reading panel by panel on or off for the series of the album, or for the album alone when it has none.
     * The first time it is turned on on the device, what it is is told first ([PanelReading.notice]), and the mode
     * is turned on once that is closed ([closePanelNotice]).
     */
    fun setPanelMode(on: Boolean)

    /**
     * What reading panel by panel is was read, and closed by its button, a tap outside or back: the device is not
     * told again, and the mode is turned on.
     */
    fun closePanelNotice()

    /** What the album said of itself ([PanelReading.albumBanner]) was answered: it goes, and does not come back. */
    fun closeAlbumBanner()

    /**
     * Draws over the pages the zones they are cut into, or no longer: a build that can be debugged only. The pages
     * are cut while it is on, whether the series is read panel by panel or not.
     */
    fun showZones(shown: Boolean)
}

/** The answers to a newer position found on the server ([ComicReaderUiState.newerPage]). */
interface NewerPageActions {
    /** "Go there". */
    fun acceptNewer()

    fun ignoreNewer()
}

/**
 * What the user does in the panel at the end of the album ([ComicReaderUiState.endPanel]). A rating chosen there
 * is sent when the album is closed or the next one read, before the reader is left; nothing else is taken
 * while it is on its way. The row of a kept PDF deletes it from the device, once the book is closed, and undoes
 * that ([DeviceCopyActions]).
 */
interface ComicEndActions : DeviceCopyActions {
    /** From 1 to 5; null takes the rating away. */
    fun setRating(stars: Int?)

    /** "Read the next one": the rating if there is one, then [ComicReaderEvent.OpenNext]. */
    fun readNext()

    /** "Close the album", or "the book": the rating if there is one, then [ComicReaderEvent.Leave]. */
    fun closeAlbum()

    /** "Try again", after a rating that could not be sent: what was asked then is asked again. */
    fun retryRating()

    /**
     * The grip, a swipe down, a tap above the panel or back: the panel goes, the reader stays on the last
     * page, and nothing is sent. The panel comes back with its stars at the next turn past the last page.
     */
    fun dismissEndPanel()
}

/** What the user does in the comics reader. */
interface ComicReaderActions :
    ComicNavigation,
    StripNavigation,
    ComicSettingsActions,
    NewerPageActions,
    ComicEndActions,
    PanelModeActions {
    fun toggleBars()

    /** The page moved under a tap on its edge, without a turn: the bars go, as they do when a page is turned. */
    fun hideBars()

    /** Tries again to open an album that could not be. */
    fun retryOpening()

    /** Back: what was read is written, then [ComicReaderEvent.Leave]. */
    fun leave()
}

/** What the screen and its window tell the reader. */
interface ComicHostEvents {
    /** The size of the window, at first and each time it changes: it decides one page or two. */
    fun windowSize(widthDp: Int, heightDp: Int)

    /** Whether the build can be debugged: only then is anything logged of the pages read panel by panel. */
    fun buildDebuggable(debuggable: Boolean)

    /** The reader is in front: reading time counts, and the server is asked for a newer position. */
    fun onForeground()

    /** The reader is no longer in front: the position and the session so far are written. */
    fun onBackground()
}
