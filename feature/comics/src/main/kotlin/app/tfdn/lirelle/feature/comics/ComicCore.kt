package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.layout.showsTwoPages
import app.tfdn.lirelle.core.comics.layout.spreadIndexOf
import app.tfdn.lirelle.core.comics.panels.PagePanels
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.comics.layout.spreads
import app.tfdn.lirelle.core.library.model.ReadingPosition
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val Percent = 100.0

/**
 * How long the reader waits.
 *
 * @property bars how long the bars stay at opening before they hide by themselves.
 * @property settle how long the reader must stay on a page before the pages around it are fetched ahead.
 */
data class ComicTimings(val bars: Duration = 4.seconds, val settle: Duration = 300.milliseconds)

/**
 * The index of the page a position is at, in an album of [pageCount] pages: the page the server numbers
 * [page], from 1; without one, the page [percent] of the album (0 to 100) was read up to.
 */
internal fun pageIndexAt(page: Int?, percent: Double, pageCount: Int): Int {
    val last = (pageCount - 1).coerceAtLeast(0)
    return (page ?: (percent * pageCount / Percent).roundToInt()).minus(1).coerceIn(0, last)
}

/**
 * What the parts of the comics reader share: the state shown, the album's pages, its reading.
 * Everything here is touched from the main thread only.
 */
internal class ComicCore(val bookId: BookId, val scope: CoroutineScope) {
    val state = MutableStateFlow(ComicReaderUiState())
    val events = Channel<ComicReaderEvent>(Channel.UNLIMITED)

    /** The album's pages, from the moment its file is known. */
    var pages: ComicPages? = null

    /** The zones of the album's pages, opened with [pages]; nothing is cut until it is asked to. */
    var panels: PagePanels? = null

    /** The series of the album, once its details are known; null for an album that belongs to none. */
    var seriesId: SeriesId? = null

    /** The position and the reading time of the album; null until it is open. */
    var session: ComicSession? = null

    /**
     * The page the reader is on: the first one of the spread it last went to. The spreads may be paired
     * again around it (a window that changes, a wide page arriving): the reader stays on the spread that
     * shows it.
     */
    var page = 0

    /** The pages the strip of an album read vertically shows; empty when it told none. */
    var strip: IntRange = IntRange.EMPTY

    /**
     * The page the pages were last fetched around: the one last jumped to or settled on; null before any was. A
     * strip that scrolled far from it has left the pages fetched ahead behind.
     */
    var fetchedAround: Int? = null

    /** The pages whose image is wider than it is high, as far as they were seen. */
    val wide = mutableSetOf<Int>()

    /** The window, as the screen last told it. */
    var widthDp = 0
    var heightDp = 0

    /** Whether the build can be debugged, as the screen last told it: the cuts of the pages are then logged. */
    var debuggable = false

    /** The position of the server offered to the user. */
    var offered: ReadingPosition? = null

    /** Told each time the spreads, or the one shown, may have changed. */
    var onLayout: () -> Unit = {}

    val current: ComicReaderUiState get() = state.value

    val ready: Boolean get() = current.phase == ComicPhase.Ready

    fun update(transform: (ComicReaderUiState) -> ComicReaderUiState) = state.update(transform)

    /**
     * Works the spreads out again, from the window, the setting and the wide pages, around [page]. Read
     * vertically, each page is a spread of its own.
     */
    fun layout() {
        if (!ready) return
        val twoPages = !current.vertical && showsTwoPages(current.settings.twoPages, widthDp, heightDp)
        if (!current.vertical) strip = IntRange.EMPTY
        val spreads = spreads(current.pageCount, wide, twoPages)
        update { it.copy(twoPages = twoPages, spreads = spreads, spreadIndex = spreadIndexOf(spreads, page)) }
        onLayout()
    }
}
