package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Direction

/*
 * The zones a page is cut into, drawn over it in a build that can be debugged: the cut is judged, and timed, on
 * real pages, device in hand.
 */

private val FilledColour = Color(0xFF00C853)
private val SparseColour = Color(0xFFD50000)

/** Under what is written: it reads on any page. */
private val Backing = Color(0xB3000000)
private val FrameWidth = 2.dp
private val LabelPadding = 3.dp
private val LabelStyle = TextStyle(color = Color.White, fontSize = 12.sp)

/**
 * The zones to draw over the pages of a spread.
 *
 * @property byPage what is known of its pages, by page: cut, or read whole.
 * @property direction the direction the album is read in: a zone's number is at the side its reading starts from.
 */
internal class ShownZones(val byPage: Map<Int, PageZones>, val direction: Direction)

/** What the zones are written with: what measures the texts, and the [direction] the album is read in. */
internal class ZoneMarks(val measurer: TextMeasurer, val direction: Direction)

/** Where [zone], told in proportions of its page, is in a [page] of that size. */
internal fun zoneRect(zone: Zone, page: Size): Rect =
    Rect(zone.left * page.width, zone.top * page.height, zone.right * page.width, zone.bottom * page.height)

/** What is written at the top of a page of its cut: how many zones, found in how long. */
internal fun zoneSummary(cut: PageZones.Cut): String = "${cut.zones.size} zones, ${cut.millis} ms"

/** Why a page is read whole, as the debug view and its log write it. */
internal fun wholeReason(why: WholePage): String = when (why) {
    WholePage.Cover -> "cover"
    WholePage.NoPanels -> "no panels"
    WholePage.NoPicture -> "no picture"
    WholePage.Failed -> "failed"
}

/** What is written at the top of a page read [whole]: that it is, and why. */
internal fun wholeSummary(whole: PageZones.Whole): String = "whole: ${wholeReason(whole.why)}"

/**
 * Draws over a page what is known of its [zones]. A page that is cut: each zone framed, in green when its box is
 * filled and in red when it is sparse, with its number in reading order, from 1, and at the top of the page what
 * [zoneSummary] says. A page read whole: only what [wholeSummary] says, at its top. Drawn in the page's own box,
 * it is zoomed and moved with it. Nothing is drawn of a page that is not known, or without [marks].
 */
internal fun Modifier.zoneOverlay(zones: PageZones?, marks: ZoneMarks?): Modifier = when {
    marks == null -> this
    zones is PageZones.Cut -> drawWithContent {
        drawContent()
        zones.zones.forEachIndexed { place, zone -> zone(zone, number = place + 1, marks) }
        summary(zoneSummary(zones), marks)
    }
    zones is PageZones.Whole -> drawWithContent {
        drawContent()
        summary(wholeSummary(zones), marks)
    }
    else -> this
}

/** What is said of the whole page, in the middle of its top. */
private fun DrawScope.summary(text: String, marks: ZoneMarks) {
    label(text, marks.measurer) { box -> Offset((size.width - box.width) / 2, 0f) }
}

/** The frame of a zone, and its [number] in its top corner, at the side reading starts from. */
private fun DrawScope.zone(zone: Zone, number: Int, marks: ZoneMarks) {
    val rect = zoneRect(zone, size)
    val colour = when (zone.kind) {
        ZoneKind.Filled -> FilledColour
        ZoneKind.Sparse -> SparseColour
    }
    drawRect(colour, rect.topLeft, rect.size, style = Stroke(FrameWidth.toPx()))
    label(number.toString(), marks.measurer) { box ->
        val fromLeft = marks.direction == Direction.LeftToRight
        Offset(if (fromLeft) rect.left else rect.right - box.width, rect.top)
    }
}

/** A text on its backing, where [place] says for the size of the whole, kept inside the page. */
private fun DrawScope.label(text: String, measurer: TextMeasurer, place: (Size) -> Offset) {
    val laid = measurer.measure(text, LabelStyle)
    val padding = LabelPadding.toPx()
    val box = Size(laid.size.width + 2 * padding, laid.size.height + 2 * padding)
    val asked = place(box)
    val corner = Offset(
        asked.x.coerceIn(0f, (size.width - box.width).coerceAtLeast(0f)),
        asked.y.coerceIn(0f, (size.height - box.height).coerceAtLeast(0f)),
    )
    drawRect(Backing, corner, box)
    drawText(laid, topLeft = corner + Offset(padding, padding))
}
