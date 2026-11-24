package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.bridge.SearchHit
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationUi
import app.tfdn.lirelle.feature.reader.text.TextAction
import java.time.Instant

private const val Hundred = 100

/** Where a brightness of the reader's own starts from until the window has said its level. */
internal const val UnknownBrightness = 0.5f

/** Why the book is not on screen. */
sealed interface ReaderFailure {
    /** The book's details or its file could not be fetched. */
    data class Download(val error: ApiError) : ReaderFailure

    /**
     * The book's text comes without its narration, which the server only gives to an account that may download:
     * this one may not.
     */
    data object DownloadNotAllowed : ReaderFailure

    /** The file is not an EPUB the reader can open: damaged, or something else under that name. */
    data object NotAnEpub : ReaderFailure

    /** The file is protected by a rights-management scheme. */
    data object Protected : ReaderFailure

    /** The page could not open the book, or did not answer in time. */
    data object Engine : ReaderFailure

    /**
     * The engine cannot read the file, a Kindle book or a FictionBook: it is protected, damaged, or not what
     * its format says.
     */
    data object CannotOpen : ReaderFailure

    /** The book has no file the reader lays out. */
    data object NoEpub : ReaderFailure

    /** The system's web view is too old for the engine: it has to be updated. */
    data object OldWebView : ReaderFailure
}

sealed interface ReaderPhase {
    /** [progress] is null until the first bytes arrive, and while the book's details are asked. */
    data class Downloading(val progress: DownloadProgress?) : ReaderPhase

    data class Failed(val reason: ReaderFailure) : ReaderPhase

    /** The file is on the device: [content] is what the web view serves to the page. */
    data class Reading(val content: BookContent) : ReaderPhase
}

/**
 * Where the reader is in the book.
 *
 * @property chapter the label of the current entry of the table of contents, [tocId] its id.
 * @property fraction from 0 to 1, over the whole book.
 * @property page the page in the current section, from 1, and [pages] how many it has; null when
 * scrolled or in a fixed layout.
 */
data class Location(
    val chapter: String?,
    val tocId: Int?,
    val fraction: Double,
    val page: Int?,
    val pages: Int?,
    val atEnd: Boolean,
) {
    /** From 0 to 100, rounded down: 100 is only shown at the very end. */
    val percent: Int get() = (fraction * Hundred).toInt().coerceIn(0, Hundred)

    val pagesLeftInChapter: Int? get() = if (page != null && pages != null) (pages - page).coerceAtLeast(0) else null

    /**
     * The share of the chapter's pages reached, from 0 to 100, rounded down: 100 on its last page.
     * Null when the pages are not counted.
     */
    val chapterPercent: Int?
        get() = if (page != null && pages != null && pages > 0) {
            (page.toLong() * Hundred / pages).toInt().coerceIn(0, Hundred)
        } else {
            null
        }
}

/** A position found on the server, newer than the reader's and somewhere else. [percent] goes from 0 to 100. */
data class OfferedPosition(val percent: Double, val updatedAt: Instant?)

enum class ReaderSheet { Contents, Settings, RunningLines, MoreOptions, Search }

/**
 * The panel shown at the end of the book.
 *
 * @property rating from 1 to 5, null when no star is chosen.
 * @property nextVolume null when the book has none, and until the server has answered.
 * @property sending the rating is on its way: the buttons wait.
 * @property failed the rating could not be sent; the same button tries again.
 * @property device what the device keeps of the book, for its row; null when it keeps nothing whole.
 * @property ratings false for a source without ratings: the panel has no stars, and sends none (spec 2c).
 */
data class EndPanel(
    val rating: Int? = null,
    val nextVolume: NextVolume? = null,
    val sending: Boolean = false,
    val failed: Boolean = false,
    val device: DeviceCopy? = null,
    val ratings: Boolean = true,
)

/**
 * The page's live selection. [bounds] are in fractions of the page, the visible part; [exactId] is
 * the highlight with exactly this range, if any.
 */
data class SelectionUi(
    val cfi: String,
    val text: String,
    val sectionIndex: Int,
    val bounds: PageBounds,
    val exactId: MarkId?,
)

/**
 * The panel of one highlight.
 *
 * @property color its colour as drawn, `#RRGGBB`.
 * @property note the note as saved, empty when none; [draft] what the note field holds.
 * @property focusNote the panel opened from "Note": the note field takes the focus.
 * @property confirmDiscard closing was asked with a changed note: whether to discard it is asked.
 */
data class HighlightPanelUi(
    val id: MarkId,
    val text: String,
    val color: String,
    val note: String,
    val draft: String,
    val focusNote: Boolean,
    val confirmDiscard: Boolean,
)

/** A deletion that can still be undone. */
sealed interface UndoUi {
    data class Highlight(val id: MarkId) : UndoUi

    data class Bookmarks(val ids: List<MarkId>) : UndoUi
}

/** The results of one section of the book, in reading order; [label] is its contents entry, null when it has none. */
data class SearchRow(val sectionIndex: Int, val label: String?, val hits: List<SearchHit>)

/**
 * The search in the book, from the moment it is opened until it is closed.
 *
 * @property query what the field holds, at most 200 characters.
 * @property searchId the search the page runs for it; null while nothing was searched.
 * @property running results may still come; [fraction] is how far the search has got, from 0 to 1.
 * @property groups the results by section, in the order they were found.
 * @property total how many results, counted as they come, then as the page gave it at the end.
 * @property truncated the page stopped at its limit: there are more.
 * @property current the result shown on the page, as an index across all the [groups]' hits.
 * @property onPage a result was shown: the page has the search's bar.
 */
data class SearchUi(
    val query: String,
    val searchId: Int?,
    val running: Boolean,
    val fraction: Double,
    val groups: List<SearchRow>,
    val total: Int,
    val truncated: Boolean,
    val current: Int?,
    val onPage: Boolean,
)

/** The tabs of the panel opened from the top bar. */
enum class PanelTab { Contents, Bookmarks, Notes }

/**
 * @property opened the page shows the book: before that, [ReaderPhase.Reading] is still an opening.
 * @property goBackShown a jump was made: "Go back" returns to where the user was.
 * @property colours those of the reading theme, for the bars, the sheets and behind the page.
 * @property externalLink a web address the book links to, waiting for the user's confirmation.
 * @property adjustingBrightness a finger is sliding along the brightness edge.
 * @property systemBrightness the level of the screen when the reader does not set it, from 0 to 1:
 *   where a brightness of the reader's own starts from.
 * @property marks the book's bookmarks and highlights, in reading order.
 * @property sectionLabels one per section of the spine: the contents entry it belongs to, null before the first.
 * @property pageBookmarks the bookmarks inside the visible page.
 * @property ribbonHidden the page said a turn is coming: the ribbon and the filled bookmark icon of
 *   the page left are hidden until the next place is known; [pageBookmarks] are kept meanwhile.
 * @property panelTab the tab the panel shows, the last one used while the book is open.
 * @property textActions what the device offers to do with the selected text, in the bar's order.
 * @property search the search in the book, null when none is open.
 * @property noteOpen the page shows the panel of a footnote.
 * @property narration the book's narration, as the reader follows it.
 * @property capabilities what the profile's source can do (spec 2c): without annotations no highlight, note nor
 *   bookmark, and the selection keeps only the system's actions and the search; without read-along no narration.
 */
data class ReaderUiState(
    val title: String = "",
    val coverUrl: String? = null,
    val phase: ReaderPhase = ReaderPhase.Downloading(null),
    val opened: Boolean = false,
    val toc: List<TocEntry> = emptyList(),
    val location: Location? = null,
    val controlsShown: Boolean = false,
    val goBackShown: Boolean = false,
    val newerPosition: OfferedPosition? = null,
    val endPanel: EndPanel? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val colours: ThemeColours = settings.theme.colours(systemDark = false),
    val rtl: Boolean = false,
    val openSheet: ReaderSheet? = null,
    val externalLink: String? = null,
    val adjustingBrightness: Boolean = false,
    val systemBrightness: Float = UnknownBrightness,
    val marks: BookMarks = BookMarks(),
    val sectionLabels: List<String?> = emptyList(),
    val pageBookmarks: List<MarkId> = emptyList(),
    val ribbonHidden: Boolean = false,
    val selection: SelectionUi? = null,
    val highlightPanel: HighlightPanelUi? = null,
    val undo: UndoUi? = null,
    val panelTab: PanelTab = PanelTab.Contents,
    val textActions: List<TextAction> = emptyList(),
    val search: SearchUi? = null,
    val noteOpen: Boolean = false,
    val narration: ReaderNarrationUi = ReaderNarrationUi(),
    val capabilities: SourceCapabilities = SourceCapabilities.Everything,
)

/** The file has a narration the source lets the reader offer: "Read from here" and the wave (spec 2c). */
val ReaderUiState.narrationOffered: Boolean
    get() = capabilities.readAlong && narration.available

/** The page shows its ribbon, and the top bar a filled bookmark: it holds one, and is not being turned. */
val ReaderUiState.ribbonShown: Boolean
    get() = pageBookmarks.isNotEmpty() && !ribbonHidden
