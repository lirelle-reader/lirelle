package app.tfdn.lirelle.feature.comics.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.layout.drawn
import app.tfdn.lirelle.core.comics.layout.openingCorner
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.feature.comics.ComicNavigation
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.R
import kotlin.math.roundToInt

/** The page view: what takes the taps and the swipes. */
const val ComicSpreadTag = "comic-spread"

/** The spreads beside the one shown: off the screen until a slide brings one in. */
const val ComicPreviousSpreadTag = "comic-spread-previous"
const val ComicNextSpreadTag = "comic-spread-next"

/** What is left of the background between two spreads. */
private val SpreadGap = 16.dp

/** The speed from which a finger flung the spread to the one beside it, in dp a second. */
private val FlingVelocity = 400.dp

/**
 * The pages of the reader: the spread shown, on the background the settings say, and the gestures that zoom it,
 * move it and turn it. The spreads before and after it stand at its sides, off the screen: a turn slides to
 * one. It fills the room it is given, and shows nothing but the background until the album is open.
 *
 * The zoom is kept here from one spread to the next, and goes back to the fit when the fit changes. In a series
 * read panel by panel, the gestures step from the whole spread to each of its panels.
 *
 * @param onToggleBars a tap in the middle of the screen.
 * @param onHideBars a tap on an edge that moved the page without turning it: a turn is the reader's to see.
 */
@Composable
fun ComicSpreadView(
    state: ComicReaderUiState,
    navigation: ComicNavigation,
    onToggleBars: () -> Unit,
    modifier: Modifier = Modifier,
    onHideBars: () -> Unit = {},
) {
    val colours = state.settings.background.colours()
    BoxWithConstraints(
        modifier.fillMaxSize().background(colours.background).clipToBounds().testTag(ComicSpreadTag),
    ) {
        val viewport = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val turns = SpreadTurns(navigation, onToggleBars, onHideBars)
        state.spread?.let { SlidingSpreads(state, it, viewport, turns, PageSetting(colours, turnActions(navigation))) }
    }
}

/**
 * The [spread] shown at the zoom of the reader, at the place it opens at or the one the user moved it to, and
 * the spreads beside it, at the same zoom and at the corner they would open at. Read panel by panel, a spread is
 * where its stop puts it, and what the stop leaves out of its room is hidden: so is the spread a step turns to,
 * from the start of the slide ([SpreadBeside]).
 */
@Composable
private fun SlidingSpreads(
    state: ComicReaderUiState,
    spread: Spread,
    viewport: Size,
    turns: SpreadTurns,
    setting: PageSetting,
) {
    val direction = state.settings.direction
    val fit = spreadFit(state.settings.fit, state.twoPages)
    // Reading panel by panel, the zoom is each stop's: turned on or off, the reader starts again from the fit.
    val zoom = remember(fit, state.panels.on) { SpreadZoom() }
    val pans = remember(fit, state.panels.on) { SpreadPans() }
    val pan = remember(pans, state.spreadIndex) { pans.shown(state.spreadIndex) }
    val slide = remember { SpreadSlide(state.spreadIndex) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val sliding = SpreadSliding(slide, state.spreadIndex, slideLane(state, viewport), turns.navigation, scope)
    val shown = laidOut(state, spread, viewport, fit)
    val frame = shown.frame(viewport, direction, state.forward)
    val placed = PlacedSpread(pan, frame)
    val places = SpreadPlaces(placed, pans, state.spreadIndex) { index, forward ->
        state.spreads.getOrNull(index)?.let { laidOut(state, it, viewport, fit).frame(viewport, direction, forward) }
    }
    val panels = panelSpread(state, shown, frame, zoom)?.let {
        PanelSteps(it, StopMoves(zoom, placed, scope, systemAnimates()), places, sliding, turns.onHideBars)
    }
    // A spread that a step turns to slides in at the stop it is to arrive at: only where panels are read.
    val beside = SpreadBeside(zoom, pans, pan) { panels != null && sliding.pending != 0 }
    val current by rememberUpdatedState(SpreadGestures(zoom, places, turns, scope, sliding, panels))
    val step = nextPlace(viewport.width, with(LocalDensity.current) { SpreadGap.toPx() }, direction)
    // Before the slide follows the reader: whether a turn is still on its way is told from where the slide was.
    if (panels != null) SideEffect { panels.settle() }
    SideEffect { sliding.follow() }
    if (panels != null) SideEffect { current.arrive() }
    val scene = Triple(state.spreadIndex, viewport, fit)
    SharpenAtRest(zoom, scene, shown.sharpTargets(), turns.navigation::sharpenPage)

    Box(Modifier.fillMaxSize().spreadGestures(haptics) { current }) {
        // In reading order: the spread before, the one shown, the one after.
        listOf(-1, 0, 1).forEach { place ->
            val index = state.spreadIndex + place
            val spread = state.spreads.getOrNull(index) ?: return@forEach
            // A spread keeps what it drew when a turn makes it the one shown.
            key(index) {
                val laid = if (place == 0) shown else laidOut(state, spread, viewport, fit)
                val slot = SpreadSlot(
                    x = { slide.position.seenFrom(state.spreadIndex, step) + place * step },
                    scale = { if (place == 0) current.scale else beside.scale(index) },
                    offset = {
                        when (place) {
                            0 -> current.offset
                            else -> beside.offset(index, laid.content, viewport, direction.openingCorner(place > 0))
                        }
                    },
                )
                // Around a panel: over the pages of its spread alone, in the room that slides with them.
                val inSlot = Modifier.besideTheSpread(place).aroundThePanel(panels != null, setting.colours) {
                    if (place == 0) pan.framed?.window else beside.framed(index)?.window
                }
                SpreadInSlot(laid, slot, setting, turns.navigation::retryPage, inSlot)
            }
        }
    }
}

/**
 * What the page view knows of reading panel by panel for the spread [shown], laid out as [frame] says; null when
 * the series is not read so: nothing is then worked out, nor kept. The walk lasts as long as the [zoom] does.
 */
@Composable
private fun panelSpread(
    state: ComicReaderUiState,
    shown: LaidSpread,
    frame: SpreadFrame,
    zoom: SpreadZoom,
): PanelSpread? {
    val reading = state.panels
    if (!reading.on) return null
    val walk = remember(zoom) { PanelWalk() }
    val stops = remember(frame, state.spread, reading.zones) { shown.stops(frame, reading.zones) }
    val fit = spreadFit(state.settings.fit, state.twoPages)
    // Worked out when a step turns the page: the stops of the spread turned to, as it will open.
    val ahead = { turns: Int ->
        state.spreads.getOrNull(state.spreadIndex + turns)?.let {
            val laid = laidOut(state, it, frame.viewport, fit)
            laid.stops(laid.frame(frame.viewport, frame.direction, turns > 0), reading.zones)
        }
    }
    return PanelSpread(walk, state.spreadIndex, stops, reading.wholePageFirst, ahead)
}

/** Where the spreads slide, in the [viewport], around the spread shown. */
@Composable
private fun slideLane(state: ComicReaderUiState, viewport: Size): SlideLane = with(LocalDensity.current) {
    SlideLane(
        width = viewport.width,
        gap = SpreadGap.toPx(),
        direction = state.settings.direction,
        room = SlideRoom(back = state.spreadIndex > 0, forward = state.spreadIndex < state.spreads.lastIndex),
        flingVelocity = FlingVelocity.toPx(),
    )
}

/** A spread beside the one shown is not read out, nor found by its pages: only by the side it stands at. */
private fun Modifier.besideTheSpread(place: Int): Modifier = when {
    place < 0 -> clearAndSetSemantics { testTag = ComicPreviousSpreadTag }
    place > 0 -> clearAndSetSemantics { testTag = ComicNextSpreadTag }
    else -> this
}

/**
 * A spread as it is laid out.
 *
 * @property pages its pages from the left of the screen to its right, with what the device has of each.
 * @property shapes the shape of each page, as its width over its height.
 * @property content the size of the spread at the fit.
 * @property zones the zones to draw over its pages; null when none is to be.
 */
private class LaidSpread(
    val pages: List<Pair<Int, PageState>>,
    val shapes: List<Float>,
    val content: Size,
    val zones: ShownZones? = null,
) {
    /** The room of the page at [place], from the left: its share of the width, by its shape. */
    fun slot(place: Int): Size = Size(content.width * shapes[place] / shapes.sum(), content.height)

    /** The spread in the [viewport], as it opens when reached going [forward]. */
    fun frame(viewport: Size, direction: Direction, forward: Boolean): SpreadFrame {
        val spans = pageSpans(shapes, content.width)
        val inReadingOrder = if (direction == Direction.RightToLeft) spans.reversed() else spans
        return SpreadFrame(viewport, content, direction.openingCorner(forward), direction, inReadingOrder)
    }

    /**
     * The stops of the spread read panel by panel, laid out as [frame] says, from what is known of its pages'
     * [zones]: empty when no page is cut, null while a page is not known.
     */
    fun stops(frame: SpreadFrame, zones: Map<Int, PageZones>): List<Rect>? {
        val drawn = pages.map { (index, _) -> index }
        val read = if (frame.direction == Direction.RightToLeft) drawn.reversed() else drawn
        val rooms = frame.pages.zip(read) { span, index ->
            StopPage(Rect(span.left, 0f, span.right, content.height), zones[index] ?: PageZones.Unknown)
        }
        return spreadStops(rooms, frame.viewport, frame.direction)
    }

    /** The pages whose picture is on the device, each with its room: those a zoom can ask sharper. */
    fun sharpTargets(): List<SharpTarget> = pages.mapIndexedNotNull { place, (index, page) ->
        (page as? PageState.Ready)?.let { SharpTarget(index, IntSize(it.width, it.height), slot(place)) }
    }
}

private fun laidOut(state: ComicReaderUiState, spread: Spread, viewport: Size, fit: Fit): LaidSpread {
    val pages = spread.drawn(state.settings.direction).map { it to state.pageState(it) }
    val shapes = pageShapes(pages.map { (_, page) -> page.imageSize() }, viewport)
    val zones = state.panels.zones.takeIf { state.zonesShown && it.isNotEmpty() }
    return LaidSpread(
        pages = pages,
        shapes = shapes,
        content = fittedSize(shapes, viewport, fit),
        zones = zones?.let { ShownZones(it, state.settings.direction) },
    )
}

/**
 * Where a spread is drawn, read when it is drawn.
 *
 * @property x how far the screen-wide room of the spread is from the screen, sideways.
 * @property scale the zoom, and [offset] the place of the spread in its room.
 */
private class SpreadSlot(val x: () -> Float, val scale: () -> Float, val offset: () -> Offset)

/**
 * A spread in a room as large as the screen, which cuts what is out of it: the spread beside starts there. The
 * zones of its pages, when they are shown, are drawn with the pages: they are zoomed and moved as they are.
 */
@Composable
private fun SpreadInSlot(
    laid: LaidSpread,
    slot: SpreadSlot,
    setting: PageSetting,
    onRetry: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val marks = laid.zones?.let { ZoneMarks(rememberTextMeasurer(), it.direction) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = slot.x()
                clip = true
            }
            .then(modifier),
    ) {
        Row(
            Modifier
                .fittedTo(laid.content)
                .graphicsLayer {
                    val offset = slot.offset()
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = slot.scale()
                    scaleY = slot.scale()
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            laid.pages.forEachIndexed { place, (index, page) ->
                ComicPage(
                    index = index,
                    page = page,
                    slot = laid.slot(place),
                    setting = setting,
                    onRetry = { onRetry(index) },
                    modifier = Modifier
                        .weight(laid.shapes[place])
                        .fillMaxHeight()
                        .zoneOverlay(laid.zones?.byPage?.get(index), marks),
                )
            }
        }
    }
}

/** The size of the image of a page that is on the device, in pixels. */
private fun PageState.imageSize(): Size? =
    (this as? PageState.Ready)?.let { Size(it.width.toFloat(), it.height.toFloat()) }

/**
 * Lays the spread out at its fitted size, from the top left corner of the screen, whatever the room there:
 * a spread larger than the screen is not shrunk, and the zoom and the offset are counted from that corner.
 */
private fun Modifier.fittedTo(content: Size): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(content.width.roundToInt(), content.height.roundToInt()))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
}

/** "Next page" and "Previous page", for a screen reader. */
@Composable
private fun turnActions(navigation: ComicNavigation): List<CustomAccessibilityAction> {
    val next = stringResource(R.string.comics_next_page)
    val previous = stringResource(R.string.comics_previous_page)
    return remember(navigation, next, previous) {
        listOf(
            CustomAccessibilityAction(next) {
                navigation.next()
                true
            },
            CustomAccessibilityAction(previous) {
                navigation.previous()
                true
            },
        )
    }
}
