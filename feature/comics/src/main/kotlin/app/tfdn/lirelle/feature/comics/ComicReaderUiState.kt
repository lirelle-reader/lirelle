package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.layout.pageNumberOf
import app.tfdn.lirelle.core.comics.layout.percentOf
import app.tfdn.lirelle.core.comics.layout.spreadIndexOf
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError

/** Why an album could not be opened. */
sealed interface ComicFailure {
    /** The book's details or its page count could not be had. */
    data class Load(val error: ApiError) : ComicFailure

    /** The book has no comic to read: no comic file, or one without a page. */
    data object NoComic : ComicFailure

    /** The document asks for a password: the device cannot open it, however many times it tries. */
    data object Protected : ComicFailure

    /** The document that came is not one of its format, or not a whole one; it is fetched anew at the next try. */
    data object Damaged : ComicFailure

    /** The copy the device keeps of the document is damaged: a new try would open the same file. */
    data object KeptDamaged : ComicFailure
}

sealed interface ComicPhase {
    /**
     * The book's details, its page count and its position are being asked; a document drawn on the device is
     * being fetched whole ([ComicReaderUiState.opening]).
     */
    data object Loading : ComicPhase

    /** The album could not be opened; the opening can be tried again. */
    data class Failed(val reason: ComicFailure) : ComicPhase

    /** The album is open: its spreads are known, its pages come as they are fetched. */
    data object Ready : ComicPhase
}

/** The series of the album and its number in it, as the server gives them: the screen writes the line. */
data class SeriesLine(val name: String, val index: String?)

/**
 * What the bar says of the pages shown, as numbers from 1: "Page [first] of [count]", or with two pages
 * "Pages [first]-[last] of [count]".
 */
data class PageLabel(val first: Int, val last: Int?, val count: Int)

/**
 * The panel shown at the end of the album.
 *
 * @property rating from 1 to 5, null when no star is chosen.
 * @property nextVolume the series' next album; null when there is none, and until the server has answered.
 * @property sending the rating is on its way: the buttons wait.
 * @property failed the rating could not be sent; what was asked can be asked again.
 * @property device what the device keeps of the book, for its row; null when it keeps nothing whole of it.
 * @property ratings false for a source without ratings: the panel has no stars, and sends none (spec 2c).
 */
data class ComicEndPanel(
    val rating: Int? = null,
    val nextVolume: NextVolume? = null,
    val sending: Boolean = false,
    val failed: Boolean = false,
    val device: DeviceCopy? = null,
    val ratings: Boolean = true,
)

/**
 * Reading panel by panel, as the page view and the bars need it.
 *
 * @property offered whether the album can be read so: false for an album whose pages are never cut (a PDF), and
 *   until the album is open.
 * @property on whether the series is read so; never for an album that is not [offered] it.
 * @property wholePageFirst whether each page is shown whole before its first panel.
 * @property zones what is known of the pages of the spread shown and of the spreads before and after it, by page,
 *   cut or read whole, while the pages are cut; a page that is not there is not known.
 * @property notice whether what reading panel by panel is, and where it falls short, is being told: the first time
 *   the mode is turned on on the device, before it is.
 * @property albumBanner whether the album is being said to lend itself badly to it: few of the pages read could
 *   be cut. Said once for an album.
 */
data class PanelReading(
    val offered: Boolean = false,
    val on: Boolean = false,
    val wholePageFirst: Boolean = true,
    val zones: Map<Int, PageZones> = emptyMap(),
    val notice: Boolean = false,
    val albumBanner: Boolean = false,
)

/**
 * What the comics reader shows. Pages are indexes from 0, but in [label] and [newerPage].
 *
 * @property kind what the pages are: a comic's, a PDF's, a DjVu's. A comic until the book's details are known.
 * @property opening how far the fetch of a document drawn on the device is, while it opens; null otherwise.
 * @property series null for an album that belongs to no series.
 * @property directionScope what the reading direction is remembered for: the series, or the album alone.
 * @property twoPages whether the window and the setting show two pages side by side.
 * @property spreads every spread of the album, in reading order, whatever the reading direction.
 * @property spreadIndex the spread shown, in [spreads].
 * @property forward whether the spread shown was reached going forward: it then opens at its beginning, else
 *   at its end.
 * @property previewPage the page the slider would go to, while it is dragged; null otherwise.
 * @property pages what the device has of the pages of the spread shown and of the spreads before and after
 *   it, by page; a page that is not there is loading ([pageState]).
 * @property newerPage the number of the page a newer position on the server is at, offered to the user.
 * @property endPanel the panel at the end of the album, once the user turned past its last page; null while
 *   it is not shown.
 * @property zonesShown whether the zones of the pages are drawn over them: asked in a build that can be debugged,
 *   and never kept. What is drawn is in [panels].
 * @property panels reading panel by panel: whether the series is read so, and what is known of the pages near.
 */
data class ComicReaderUiState(
    val phase: ComicPhase = ComicPhase.Loading,
    val title: String = "",
    val kind: PagedKind = PagedKind.Comic,
    val opening: OpeningProgress? = null,
    val series: SeriesLine? = null,
    val directionScope: DirectionScope? = null,
    val pageCount: Int = 0,
    val twoPages: Boolean = false,
    val spreads: List<Spread> = emptyList(),
    val spreadIndex: Int = 0,
    val forward: Boolean = true,
    val settings: ComicSettings = ComicSettings(),
    val barsShown: Boolean = false,
    val previewPage: Int? = null,
    val pages: Map<Int, PageState> = emptyMap(),
    val newerPage: Int? = null,
    val endPanel: ComicEndPanel? = null,
    val zonesShown: Boolean = false,
    val panels: PanelReading = PanelReading(),
) {
    /** The spread shown; null until the album is open. */
    val spread: Spread? get() = spreads.getOrNull(spreadIndex)

    /** The spread the bar tells about: the one the slider previews while it is dragged, else the one shown. */
    private val told: Spread?
        get() = previewPage?.let { spreads.getOrNull(spreadIndexOf(spreads, it)) } ?: spread

    /** What the bar says of the pages; null until the album is open. */
    val label: PageLabel?
        get() = told?.let { PageLabel(pageNumberOf(it.first), it.second?.let(::pageNumberOf), pageCount) }

    /** How far in the album the bar's pages are, from 0 to 100: the last page shown counts as read. */
    val percent: Float get() = told?.let { percentOf(it.pages.last(), pageCount) } ?: 0f

    /** What the device has of the page [index]: loading for a page the reader is not near. */
    fun pageState(index: Int): PageState = pages[index] ?: PageState.Loading
}

/** What the screen is asked to do, once each. */
sealed interface ComicReaderEvent {
    /** The reader is left. */
    data object Leave : ComicReaderEvent

    /** The next album of the series is to be opened in the reader, in place of this one. */
    data class OpenNext(val bookId: BookId) : ComicReaderEvent
}
