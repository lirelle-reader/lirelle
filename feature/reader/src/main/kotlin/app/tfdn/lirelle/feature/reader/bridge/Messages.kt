package app.tfdn.lirelle.feature.reader.bridge

/** What the reader page tells the host. The names on the wire are the bridge contract's. */
sealed interface PageMessage {
    /** The page is loaded and listening. */
    data object Ready : PageMessage

    data class Opened(
        val toc: List<TocEntry>,
        val dir: PageDirection,
        val fixedLayout: Boolean,
        val title: String?,
        /** One per section of the spine: the label of the contents entry it belongs to, null before the first. */
        val sectionLabels: List<String?> = emptyList(),
    ) : PageMessage

    /**
     * Where the reader is. [page] and [pages] are those of the current section, null when scrolled or in a fixed
     * layout. [moved] is false for the position the book was opened or restored at.
     *
     * The page sends the last of a burst only and never repeats an identical one on its own. [byHost] marks the
     * answer to a [HostMessage.Move]: the page sends one after every move the host asked for, even to the place it
     * already showed. A page from before that flag never sends it. [byNarration] marks a move the page made by
     * itself to follow the narration ([HostMessage.NarrationMark] with `follow`): not the user's, nor an answer.
     */
    data class Relocated(
        val cfi: String,
        val fraction: Double,
        val sectionIndex: Int,
        val sectionCount: Int,
        val page: Int?,
        val pages: Int?,
        val tocId: Int?,
        val tocLabel: String?,
        val atEnd: Boolean,
        val moved: Boolean,
        /** Bookmarks inside the visible page, at most [BridgeLimits.MaxBookmarkIds]. */
        val bookmarkIds: List<String> = emptyList(),
        /** The answer to a move the host asked for; false when absent. */
        val byHost: Boolean = false,
        /** A move the page made to follow the narration; false when absent. */
        val byNarration: Boolean = false,
        /**
         * This relocation is the user's own move (a turn, a swipe, a link), not a layout again after a resize nor
         * a place sent again; [moved] only says the user moved since the book opened. True when absent.
         */
        val userMove: Boolean = true,
    ) : PageMessage

    /**
     * A turn forward was attempted on the last page of the book: there was nothing to turn to. The end itself has
     * been reported before, as a [Relocated] with [Relocated.atEnd].
     */
    data object PastEnd : PageMessage

    /**
     * A page turn by the user is about to happen: a swipe decided to turn, or the host's `next` or `prev` came. The
     * [Relocated] that follows says where the page is, the same place again when the turn could not happen.
     */
    data object Turning : PageMessage

    /** A tap that was not on a link, in fractions of the page's size. */
    data class Tap(val x: Double, val y: Double) : PageMessage

    /** [delta] is a fraction of the screen height since the last message, up positive. */
    data class Brightness(val phase: BrightnessPhase, val delta: Double) : PageMessage

    /**
     * An internal link has already been followed by the page. An external one is only reported: [href] comes from
     * the book, can be of any scheme, and must be checked before anything is done with it.
     */
    data class Link(val href: String, val external: Boolean) : PageMessage

    data class Error(val code: PageErrorCode, val message: String) : PageMessage

    /** About the selection and the highlights. */
    sealed interface Marking : PageMessage

    /** About the search in the book. */
    sealed interface Finding : PageMessage

    /** About the panel of a footnote. */
    sealed interface Noting : PageMessage

    /** About the sentence the narration reads. */
    sealed interface Narrating : PageMessage

    /** The reader has a live selection. [text] is cut at [BridgeLimits.MaxSelectionText]; [exactId] has its range. */
    data class Selection(
        val cfi: String,
        val text: String,
        val truncated: Boolean,
        val sectionIndex: Int,
        val bounds: PageBounds,
        val highlightIds: List<String>,
        val exactId: String?,
    ) : Marking

    data object SelectionCleared : Marking

    /** A tap on a highlight, sent instead of [Tap]. */
    data class HighlightTap(val id: String, val bounds: PageBounds) : Marking

    /**
     * Up to [BridgeLimits.MaxSearchHits] hits in all, grouped by section, each section at most once. A section's
     * hits may go on in the first group of the next message. [fraction] is how far the search has got (0..1), null
     * when the page did not say.
     */
    data class SearchResults(
        val searchId: Int,
        val groups: List<SearchGroup>,
        val fraction: Double? = null,
    ) : Finding

    data class SearchProgress(val searchId: Int, val fraction: Double) : Finding

    data class SearchDone(val searchId: Int, val total: Int, val truncated: Boolean) : Finding

    /** A note reference was followed and the page shows its note. [label] is the reference's text. */
    data class NoteOpened(val href: String, val label: String?) : Noting

    data object NoteClosed : Noting

    /** The answer to [HostMessage.NarrationFind]: the narrated id to start from, null when there is none. */
    data class NarrationFound(val requestId: Int, val sectionIndex: Int, val id: String?) : Narrating

    /**
     * The answer to [HostMessage.NarrationCfi]: the position of the sentence's start, null when it is missing, and
     * how far into the book it is (0..1, as a [Relocated] there counts it), null when the page cannot tell.
     */
    data class NarrationCfiOf(val requestId: Int, val cfi: String?, val fraction: Double? = null) : Narrating

    /** The marked sentence's visibility on the page shown changed (a page turn, a resize). */
    data class NarrationVisible(val visible: Boolean) : Narrating

    /** The sentence of a [HostMessage.NarrationMark] is not in its section's document: nothing is underlined. */
    data class NarrationMissing(val sectionIndex: Int, val id: String) : Narrating
}

/** A rectangle in fractions (0..1) of the page, the visible part only. */
data class PageBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

data class SearchHit(val cfi: String, val pre: String, val match: String, val post: String)

/** The hits of one section in a [PageMessage.SearchResults]; [label] is the section's contents label. */
data class SearchGroup(val sectionIndex: Int, val label: String?, val results: List<SearchHit>)

/** One line of the flattened table of contents, in reading order. */
data class TocEntry(val id: Int, val label: String, val href: String, val depth: Int)

enum class PageDirection(val wire: String) { Ltr("ltr"), Rtl("rtl") }

enum class BrightnessPhase(val wire: String) { Start("start"), Move("move"), End("end") }

/**
 * How the page is given a book and opens it: an EPUB entry by entry; a Kindle book (MOBI, AZW, AZW3) and a
 * FictionBook as one file.
 */
enum class BookFormat(val wire: String) { Epub("epub"), Mobi("mobi"), Fb2("fb2") }

enum class PageErrorCode(val wire: String) {
    OpenFailed("open_failed"),

    /** The file given whole is not a book the engine reads: protected, damaged, or of another format. */
    CannotOpen("cannot_open"),
    SectionFailed("section_failed"),

    /** Can come instead of [PageMessage.Ready], or in answer to an `open`. */
    UnsupportedEngine("unsupported_engine"),
}

/** What the host tells the reader page. */
sealed interface HostMessage {
    /** The `type` on the wire. */
    val wire: String

    /**
     * [base] is where the book is served, starting and ending with a slash (`/book/<token>/`): the entries of
     * an EPUB, or the one file of a book of another [format]. [fraction] is used when [cfi] is null or cannot
     * be resolved.
     */
    data class Open(
        val base: String,
        val cfi: String?,
        val fraction: Double?,
        val settings: PageSettings,
        val format: BookFormat = BookFormat.Epub,
    ) : HostMessage {
        override val wire get() = "open"
    }

    /** A move asked by the host, answered by a [PageMessage.Relocated] with [PageMessage.Relocated.byHost]. */
    sealed interface Move : HostMessage

    /** Exactly one target. */
    sealed interface GoTo : Move {
        override val wire get() = "goTo"

        data class Cfi(val cfi: String) : GoTo

        data class Href(val href: String) : GoTo

        data class Fraction(val fraction: Double) : GoTo
    }

    data object Next : HostMessage {
        override val wire get() = "next"
    }

    data object Prev : HostMessage {
        override val wire get() = "prev"
    }

    /** `settings` on the wire. */
    data class ApplySettings(val settings: PageSettings) : HostMessage {
        override val wire get() = "settings"
    }

    /** `highlights`: replaces the set. */
    data class SetHighlights(val items: List<PageHighlight>) : HostMessage {
        override val wire get() = "highlights"
    }

    data class PutHighlight(val item: PageHighlight) : HostMessage {
        override val wire get() = "putHighlight"
    }

    data class RemoveHighlight(val id: String) : HostMessage {
        override val wire get() = "removeHighlight"
    }

    /** `bookmarks`: replaces the set. */
    data class SetBookmarks(val items: List<PageBookmark>) : HostMessage {
        override val wire get() = "bookmarks"
    }

    data object ClearSelection : HostMessage {
        override val wire get() = "clearSelection"
    }

    /** Cancels any search in progress; [query] is 2 to 200 characters. */
    data class Search(val searchId: Int, val query: String) : HostMessage {
        override val wire get() = "search"
    }

    /** Stops, keeps the outlines. */
    data object CancelSearch : HostMessage {
        override val wire get() = "cancelSearch"
    }

    /** Stops, removes the outlines. */
    data object ClearSearch : HostMessage {
        override val wire get() = "clearSearch"
    }

    /** Goes to the result at [cfi] and marks it as the current one. */
    data class ShowSearchResult(val cfi: String) : Move {
        override val wire get() = "showSearchResult"
    }

    data object CloseNote : HostMessage {
        override val wire get() = "closeNote"
    }

    /**
     * The sentence heard: the element [id] of the section [sectionIndex], underlined, dotted while [paused]. With
     * [follow], the page goes to it when it is not on the page shown, and says so with
     * [PageMessage.Relocated.byNarration]; without, nothing moves.
     */
    data class NarrationMark(val sectionIndex: Int, val id: String, val paused: Boolean, val follow: Boolean) :
        HostMessage {
        override val wire get() = "narrationMark"
    }

    /** No sentence is heard any more: the underline goes. */
    data object NarrationClear : HostMessage {
        override val wire get() = "narrationClear"
    }

    /**
     * Which of [ids], the narrated ids of the section in reading order, to start from: answered by a
     * [PageMessage.NarrationFound]. At most [BridgeLimits.MaxNarrationIds] are sent, the first ones. For the page,
     * [prefer] (one of [ids], where the narration was last heard) is the answer when it shows on the page.
     */
    data class NarrationFind(
        val requestId: Int,
        val sectionIndex: Int,
        val ids: List<String>,
        val what: NarrationTarget,
        val prefer: String? = null,
    ) : HostMessage {
        override val wire get() = "narrationFind"
    }

    /** The position of the start of a sentence: answered by a [PageMessage.NarrationCfiOf]. */
    data class NarrationCfi(val requestId: Int, val sectionIndex: Int, val id: String) : HostMessage {
        override val wire get() = "narrationCfi"
    }
}

/**
 * What a [HostMessage.NarrationFind] starts from: the first sentence on the page shown, else the first after it;
 * or the sentence holding the start of the selection, else the first after it.
 */
enum class NarrationTarget(val wire: String) { Page("page"), Selection("selection") }

data class PageHighlight(
    val id: String,
    val cfi: String,
    /** `#RRGGBB`. */
    val color: String,
    val style: PageHighlightStyle,
    val note: Boolean,
)

data class PageBookmark(val id: String, val cfi: String)

enum class PageHighlightStyle(val wire: String) {
    Highlight("highlight"),
    Underline("underline"),
    Strikethrough("strikethrough"),
    Squiggly("squiggly"),
}

enum class PageBlend(val wire: String) { Multiply("multiply"), Normal("normal") }

/** The texts of the note panel, in the language of the app. */
data class PageNoteLabels(val goTo: String, val title: String)

/** The `Settings` object of the contract. Colours are `#rrggbb`; [maxColumns] is 1 or 2. */
data class PageSettings(
    val background: String,
    val foreground: String,
    val link: String,
    val dark: Boolean,
    val font: PageFont,
    val fontSizePx: Int,
    val lineHeight: Double,
    val marginPercent: Double,
    val align: PageAlign,
    val hyphenate: Boolean,
    val flow: PageFlow,
    val maxColumns: Int,
    val brightnessEdge: PageBrightnessEdge,
    val highlightBlend: PageBlend = PageBlend.Multiply,
    /** 0.1..1. */
    val highlightOpacity: Double = DefaultHighlightOpacity,
    /** `#rrggbb`. */
    val searchColor: String = DefaultSearchColor,
    val footnotePanel: Boolean = true,
    val noteLabels: PageNoteLabels,
)

private const val DefaultHighlightOpacity = 0.3
private const val DefaultSearchColor = "#d97706"

enum class PageFont(val wire: String) { Book("book"), Serif("serif"), Sans("sans"), Dyslexic("dyslexic") }

enum class PageAlign(val wire: String) { Book("book"), Justify("justify"), Start("start") }

enum class PageFlow(val wire: String) { Paginated("paginated"), Scrolled("scrolled") }

enum class PageBrightnessEdge(val wire: String) { Left("left"), Right("right"), Off("off") }
