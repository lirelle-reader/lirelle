package app.tfdn.lirelle.feature.comics.strip

import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** The list of the strip. */
const val ComicStripTag = "comic-strip"

/** The widest the strip is: about a phone held upright. A wider window shows its background on both sides. */
internal val StripMaxWidth = 600.dp

/** How far the finger pulls on, past the bottom of the last page, for the end of the album to open. */
private val PastEndPull = 64.dp

/** Under this many pixels left unmoved of what a step asked, the strip is not held by one of its ends. */
private const val StepSlack = 0.5f

/**
 * The pages of an album read vertically: one under the other, edge to edge, in a strip as wide as the window up
 * to [StripMaxWidth], centred beyond; the finger scrolls it, a tap in the top or bottom quarter of the window
 * moves it by about a screen, a tap between shows or hides the bars. Taps that follow one another faster than
 * the strip moves are each taken from where the one before leads to, as they are on pages that are turned. It
 * draws no background: the screen's shows beside the strip, and under a slice that is not decoded yet.
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
        KeepsPlace(list, frame)
        val scope = rememberCoroutineScope()
        val hands by rememberUpdatedState(StripHands(strip, onToggleBars, onHideBars))
        val moves = remember(list, scope) { StripMoves(list, scope) { hands } }
        val pull = with(LocalDensity.current) { PastEndPull.toPx() }
        val pastEnd = remember(list, pull) { PastEnd(list, pull) { hands.strip.stripPastEnd() } }
        val drawing = stripDrawing(state.settings.background, navigation, pictures)

        StripReports(list, state, frame.layout, StripFollowed(strip, moves))
        LaunchedEffect(list, pastEnd, moves) {
            list.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) {
                    // The finger takes the strip from the step a tap gave it.
                    moves.stop()
                    pastEnd.start()
                    hands.onHideBars()
                }
            }
        }
        // Taps are taken over the whole window, beside the strip too; a page that failed keeps those on its
        // button.
        Box(Modifier.fillMaxSize().stripTaps(moves)) {
            // A strip of another width is another list of the same place: its items are measured anew at once,
            // not a frame after the place was asked for, when the rows asked for would be those of the old ones.
            key(frame.width) {
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
}

/**
 * Keeps the strip on the rows it shows when it is laid out again and the list would not by itself: in a window
 * of another width (the device turned, a window resized) a tall page is cut in other slices, and a page that turns
 * out unreadable in parts is one item where it was several. The list would stay on an item of the same number,
 * which may be another page's, and the page read would change without the user moving the strip.
 *
 * The strip is put back as far into the page at its top, before the list is measured with its new items. It is
 * not a scroll: the strip is not said moved by the user. A scroll under way stops there.
 */
@Composable
private fun KeepsPlace(list: LazyListState, frame: StripFrame) {
    // The strip the list was last measured with.
    val laid = remember(list) { arrayOf(frame) }
    SideEffect {
        val before = laid[0]
        laid[0] = frame
        if (before !== frame && !frame.follows(before, list.firstVisibleItemIndex)) {
            val place = before.placeOf(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            val spot = frame.itemAt(place)
            list.requestScrollToItem(spot.item, spot.offset)
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
 * Who is told of the strip, and what moves it besides the list itself.
 *
 * @property reader what the strip tells.
 * @property moves the steps taps give the strip.
 */
private class StripFollowed(val reader: StripNavigation, val moves: StripMoves)

/**
 * Tells the reader what the strip shows, and takes the strip where the reader lands.
 *
 * The pages shown are told when they change, not as the strip moves under them: each time, the reader fetches
 * around the page at the top. They are not told while the end of the album is shown, which reads the last page
 * wherever the strip is, nor between a landing and the scroll to it: the reader is already on the page landed
 * on, and would be sent back to the one the strip is leaving.
 *
 * With them goes whether the user moved the strip since it opened, or since it last went where the reader landed:
 * a finger, a fling, the step of a tap, a service that scrolls. The scroll to a page landed on is not the user's.
 *
 * The strip is said at rest when a scroll or a step ends, after the pages it then shows: an album that opens was
 * not moved, and says nothing.
 */
@Composable
private fun StripReports(list: LazyListState, state: ComicReaderUiState, layout: StripLayout, followed: StripFollowed) {
    val items by rememberUpdatedState(layout)
    val landing by rememberUpdatedState(state.landing)
    val ended by rememberUpdatedState(state.endPanel != null)
    val reader by rememberUpdatedState(followed.reader)
    val moves = followed.moves
    // The landing the strip last scrolled for: it opened on the page of the one it starts with.
    var landed by remember { mutableIntStateOf(state.landing.serial) }
    LaunchedEffect(list, moves, state.landing.serial) {
        val serial = landing.serial
        if (serial == landed) return@LaunchedEffect
        // A step under way would go on from the page landed on.
        moves.stop()
        try {
            list.scrollToItem(items.itemOf(landing.page))
        } finally {
            // Even when a finger on the strip kept it from scrolling: the strip tells again where it is.
            landed = serial
        }
    }
    LaunchedEffect(list, moves) {
        var told: StripTold? = null
        var scrolling = false
        var scrolled = false
        var at = landed
        snapshotFlow {
            val arrived = landing.serial == landed
            StripReport(
                shown = if (ended || !arrived) null else list.shown(),
                scrolling = arrived && (list.isScrollInProgress || moves.stepping),
                landed = landed,
            )
        }.collect { report ->
            if (report.landed != at) {
                at = report.landed
                scrolled = false
            }
            if (report.scrolling) scrolled = true
            report.shown?.let { StripTold(it, scrolled) }?.takeIf { it != told }?.let {
                told = it
                reader.stripShows(it.shown.first, it.shown.last, it.shown.atEnd, it.scrolled)
            }
            if (scrolling && !report.scrolling) reader.stripRested()
            scrolling = report.scrolling
        }
    }
}

/**
 * The pages the strip shows, from [first] at the top of the screen to [last]; [atEnd] at the very bottom of the
 * strip, where the last page of the album is the one read: short pages above it would otherwise keep it from ever
 * being read.
 */
private data class StripShown(val first: Int, val last: Int, val atEnd: Boolean)

/** What the reader was last told: the pages [shown], and whether the user had [scrolled] the strip. */
private data class StripTold(val shown: StripShown, val scrolled: Boolean)

/**
 * What the strip has to tell: the pages it [shown], null when there is nothing to tell, whether the user's hand
 * moves it ([scrolling]), and the landing it last went to ([landed]).
 */
private data class StripReport(val shown: StripShown?, val scrolling: Boolean, val landed: Int)

/**
 * The pages the list shows; null while nothing is laid out.
 *
 * Pages are told from the keys of the items laid out, not from their indexes: between the moment a page turns
 * out to be several slices and the moment the list is laid out again, an index is another page's.
 */
private fun LazyListState.shown(): StripShown? {
    val info = layoutInfo
    val top = info.visibleItemsInfo.firstOrNull() ?: return null
    val bottom = info.visibleItemsInfo.last()
    val first = pageOfStripKey(top.key) ?: return null
    val last = pageOfStripKey(bottom.key) ?: return null
    return StripShown(first, last, atEnd = bottom.index == info.totalItemsCount - 1 && !canScrollForward)
}

/** What the gestures of the strip ask of the reader: read when a gesture ends, never kept. */
private class StripHands(val strip: StripNavigation, val onToggleBars: () -> Unit, val onHideBars: () -> Unit)

/**
 * The moves a tap gives the strip: a step of about a screen, which the strip takes a moment to make. A tap that
 * comes meanwhile is taken from where the step leads to: two quick taps forward move the strip by two steps, and
 * a tap between the edges leaves the step to end. A tap towards an end the strip is held by starts no step: the
 * strip would be said moved by the user where it stayed.
 *
 * A step moves the strip without the list being told it scrolls: a list that scrolls keeps the finger put down
 * on it for itself, to stop, and the second of two quick taps would be lost and stop the first midway. So a
 * finger put down on a strip that was flung stops it and is no tap, while one put down on a strip that steps is
 * a tap, or, if it drags, takes the strip ([stop]).
 */
private class StripMoves(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val hands: () -> StripHands,
) {
    private var step: Job? = null

    /** How far the step under way still has to go, in pixels, forward when positive; 0 without one. */
    private var left = 0f

    /** Whether the strip moves by a step: the list does not say it scrolls then. */
    var stepping by mutableStateOf(false)
        private set

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

    /** A finger drags the strip, or the reader lands elsewhere: the step under way stops where it is. */
    fun stop() {
        step?.cancel()
        over()
    }

    private fun by(distance: Float) {
        // From where the step before leads to, not from where the strip is on its way there.
        val whole = left + distance
        val held = if (whole > 0f) !list.canScrollForward else !list.canScrollBackward
        if (held) {
            // Nowhere to go that way: the step under way, which this tap takes back, stops too.
            stop()
            return
        }
        step?.cancel()
        left = whole
        stepping = true
        step = scope.launch {
            var done = 0f
            try {
                animate(initialValue = 0f, targetValue = whole) { value, _ ->
                    val asked = value - done
                    val moved = list.dispatchRawDelta(asked)
                    done += moved
                    left = whole - done
                    // An end of the strip: there is nowhere further to go.
                    if (abs(asked - moved) > StepSlack) cancel()
                }
            } finally {
                // Not when another step took its place, or it was stopped: what is left is no longer its own.
                if (step === coroutineContext.job) over()
            }
        }
    }

    private fun over() {
        step = null
        left = 0f
        stepping = false
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
