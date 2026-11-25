package app.tfdn.lirelle.feature.comics.strip

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.feature.comics.ComicNavigation
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.StripNavigation
import app.tfdn.lirelle.feature.comics.page.PageSetting
import app.tfdn.lirelle.feature.comics.page.colours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The list of the strip. */
const val ComicStripTag = "comic-strip"

/** The widest the strip is: about a phone held upright. A wider window shows its background on both sides. */
internal val StripMaxWidth = 600.dp

/** How far the finger pulls on, past the bottom of the last page, for the end of the album to open. */
private val PastEndPull = 64.dp

/**
 * The pages of an album read vertically: one under the other, edge to edge, in a strip as wide as the window up
 * to [StripMaxWidth], centred beyond; the finger scrolls it, a tap in the top or bottom quarter of the window
 * moves it by about a screen, a tap between shows or hides the bars. It draws no background: the screen's shows
 * beside the strip, and under a slice that is not decoded yet.
 *
 * The strip tells the reader the pages it shows as they change ([StripNavigation]), and goes where the reader
 * lands ([ComicReaderUiState.landing]).
 *
 * @param onToggleBars a tap between the top and bottom quarters of the window.
 * @param onHideBars the strip moved under a tap or a finger.
 */
@Composable
internal fun ComicStripView(
    state: ComicReaderUiState,
    navigation: ComicNavigation,
    strip: StripNavigation,
    onToggleBars: () -> Unit,
    onHideBars: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = min(maxWidth, StripMaxWidth)
        val window = IntSize(with(LocalDensity.current) { width.roundToPx() }, constraints.maxHeight)
        val pictures = remember { StripPictures() }
        // Laid out again when a page comes or goes, not as the strip scrolls. What the pictures learn there is
        // what the reader says of its files: the same however many times it is learnt.
        val frame = remember(state.pages, state.pageCount, window, pictures.whole) {
            pictures.frame(state.pages, state.pageCount, window)
        }
        // On the page read. The place of the list is not kept with the screen's: the items of the strip are not
        // the same from one opening to the next, and the item it was on could be another page's.
        val list = remember { LazyListState(frame.layout.itemOf(state.spread?.first ?: 0)) }
        val scope = rememberCoroutineScope()
        val hands by rememberUpdatedState(StripHands(strip, onToggleBars, onHideBars))
        val moves = remember(list, scope) { StripMoves(list, scope) { hands } }
        val pull = with(LocalDensity.current) { PastEndPull.toPx() }
        val pastEnd = remember(list, pull) { PastEnd(list, pull) { hands.strip.stripPastEnd() } }
        val drawing = stripDrawing(state.settings.background, navigation, pictures)

        StripReports(list, state, frame.layout, strip)
        LaunchedEffect(list, pastEnd) {
            list.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) {
                    pastEnd.start()
                    hands.onHideBars()
                }
            }
        }
        // Taps are taken over the whole window, beside the strip too; a page that failed keeps those on its
        // button.
        Box(Modifier.fillMaxSize().stripTaps(moves)) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(width)
                    .fillMaxHeight()
                    .nestedScroll(pastEnd)
                    .testTag(ComicStripTag),
                state = list,
            ) {
                items(
                    count = frame.layout.size,
                    key = frame::keyOf,
                    contentType = { frame.rooms[frame.layout.pageOf(it)].slices.isNotEmpty() },
                ) { item ->
                    val index = frame.layout.pageOf(item)
                    val place = StripItem(index, frame.layout.sliceOf(item), frame.rooms[index], frame.width)
                    StripPage(place, state.pageState(index), drawing)
                }
            }
        }
    }
}

/**
 * What the items of the strip are drawn with, the same from one drawing to the next so that a page is not drawn
 * again when nothing of it changed. The pictures of tall pages are closed when the strip goes.
 */
@Composable
private fun stripDrawing(background: Background, navigation: ComicNavigation, pictures: StripPictures): StripDrawing {
    val decoders = remember { SliceDecoders() }
    DisposableEffect(decoders) { onDispose { decoders.close() } }
    val asked by rememberUpdatedState(navigation)
    // No page is turned: a screen reader moves the strip as it does any list.
    val setting = remember(background) { PageSetting(background.colours(), turning = emptyList()) }
    return remember(setting, decoders, pictures) {
        StripDrawing(setting, decoders, onRetry = { asked.retryPage(it) }, onUnreadable = pictures::unreadable)
    }
}

/**
 * Tells the reader what the strip shows, and takes the strip where the reader lands.
 *
 * The pages shown are told when they change, not as the strip moves under them: each time, the reader fetches
 * around the page at the top. They are not told while the end of the album is shown, which reads the last page
 * wherever the strip is, nor between a landing and the scroll to it: the reader is already on the page landed
 * on, and would be sent back to the one the strip is leaving.
 *
 * The strip is said at rest when a scroll ends, after the pages it then shows: an album that opens was not
 * moved, and says nothing.
 */
@Composable
private fun StripReports(list: LazyListState, state: ComicReaderUiState, layout: StripLayout, strip: StripNavigation) {
    val items by rememberUpdatedState(layout)
    val landing by rememberUpdatedState(state.landing)
    val lastPage by rememberUpdatedState(state.pageCount - 1)
    val ended by rememberUpdatedState(state.endPanel != null)
    val reader by rememberUpdatedState(strip)
    // The landing the strip last scrolled for: it opened on the page of the one it starts with.
    var landed by remember { mutableIntStateOf(state.landing.serial) }
    LaunchedEffect(list, state.landing.serial) {
        val serial = landing.serial
        if (serial == landed) return@LaunchedEffect
        try {
            list.scrollToItem(items.itemOf(landing.page))
        } finally {
            // Even when a finger on the strip kept it from scrolling: the strip tells again where it is.
            landed = serial
        }
    }
    LaunchedEffect(list) {
        var told: StripShown? = null
        var scrolling = false
        snapshotFlow {
            val quiet = ended || landing.serial != landed
            StripReport(if (quiet) null else list.shown(lastPage), list.isScrollInProgress)
        }.collect { report ->
            report.shown?.takeIf { it != told }?.let {
                told = it
                reader.stripShows(it.first, it.last)
            }
            if (scrolling && !report.scrolling) reader.stripRested()
            scrolling = report.scrolling
        }
    }
}

/** The pages the strip shows: [first] is the one read, [last] the last one on screen. */
private data class StripShown(val first: Int, val last: Int)

/** What the strip has to tell: the pages it [shown], null when there is nothing to tell, and whether it moves. */
private data class StripReport(val shown: StripShown?, val scrolling: Boolean)

/**
 * The pages the list shows, in an album whose last page is [lastPage]; null while nothing is laid out. The page
 * read is the one at the top of the screen; at the very bottom of the strip it is the last one, which short
 * pages above it would otherwise keep from ever being read.
 *
 * Pages are told from the keys of the items laid out, not from their indexes: between the moment a page turns
 * out to be several slices and the moment the list is laid out again, an index is another page's.
 */
private fun LazyListState.shown(lastPage: Int): StripShown? {
    val info = layoutInfo
    val top = info.visibleItemsInfo.firstOrNull() ?: return null
    val bottom = info.visibleItemsInfo.last()
    val first = pageOfStripKey(top.key) ?: return null
    val last = pageOfStripKey(bottom.key) ?: return null
    val atEnd = bottom.index == info.totalItemsCount - 1 && !canScrollForward
    return if (atEnd) StripShown(lastPage, lastPage) else StripShown(first, last)
}

/** What the gestures of the strip ask of the reader: read when a gesture ends, never kept. */
private class StripHands(val strip: StripNavigation, val onToggleBars: () -> Unit, val onHideBars: () -> Unit)

/**
 * The moves a tap gives the strip. A finger put down on a strip that is moving, flung or stepping, stops it and
 * is no tap: the list keeps it for itself.
 */
private class StripMoves(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val hands: () -> StripHands,
) {
    /** A tap at [y] in a window [height] high. */
    fun tapped(y: Float, height: Float) {
        when (stripTap(y, height)) {
            StripTap.Bars -> hands().onToggleBars()
            StripTap.Back -> {
                hands().onHideBars()
                by(-stripStep(height))
            }
            StripTap.Forward -> {
                hands().onHideBars()
                if (list.canScrollForward) by(stripStep(height)) else hands().strip.stripPastEnd()
            }
        }
    }

    private fun by(distance: Float) {
        scope.launch { list.animateScrollBy(distance) }
    }
}

private fun Modifier.stripTaps(moves: StripMoves): Modifier = pointerInput(moves) {
    detectTapGestures { moves.tapped(it.y, size.height.toFloat()) }
}

/**
 * The finger that goes on pulling once the strip is at the bottom of its last page: past [threshold] pixels of
 * it, [onPastEnd], once for a drag. A fling that reaches the bottom asks nothing.
 */
private class PastEnd(
    private val list: LazyListState,
    private val threshold: Float,
    private val onPastEnd: () -> Unit,
) : NestedScrollConnection {
    private var pulled = 0f
    private var asked = false

    /** A drag starts. */
    fun start() {
        pulled = 0f
        asked = false
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (asked || source != NestedScrollSource.UserInput || list.canScrollForward) return Offset.Zero
        // What the list left of a move forward: it is at its end.
        pulled += (-available.y).coerceAtLeast(0f)
        if (pulled >= threshold) {
            asked = true
            onPastEnd()
        }
        return Offset.Zero
    }
}
