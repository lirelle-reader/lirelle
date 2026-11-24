package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * What is around a panel, for a reader that reads panel by panel: the reader's own background, drawn over
 * everything the stop leaves out, so that only the panel is read.
 */

/**
 * Hides, under the [colour] of the reader's background, all that is drawn here but the [window]: the part of the
 * screen a stop leaves to see, read when it is drawn, so that it follows the spread on its way to a stop. With no
 * window (null) nothing is hidden.
 */
internal fun Modifier.stopMask(colour: Color, window: () -> Rect?): Modifier = drawWithContent {
    drawContent()
    window()?.let { drawAround(seenWindow(it, size), colour) }
}

/**
 * The mask of one spread, put on the room it is drawn in: it slides with the spread, and hides nothing of the
 * spread beside it. In a series read panel by panel ([read]), what the stop that frames the spread leaves out of
 * its [window] is under the background of the [colours]; in any other series nothing is added.
 */
internal fun Modifier.aroundThePanel(read: Boolean, colours: SpreadColours, window: () -> Rect?): Modifier =
    if (read) stopMask(colours.background, window) else this

/**
 * What a [window] leaves to see of a [screen]: the part of it that is on the screen, widened to whole pixels, so
 * that nothing is half hidden at its edges. All of the screen for a window that is the screen or more, and empty
 * for one that is nowhere on it.
 */
internal fun seenWindow(window: Rect, screen: Size): Rect = Rect(
    left = max(floor(window.left), 0f),
    top = max(floor(window.top), 0f),
    right = min(ceil(window.right), screen.width),
    bottom = min(ceil(window.bottom), screen.height),
)

/**
 * Draws the [colour] over all that is drawn here but what is [seen]: over it and under it from one side to the
 * other, then at its left and at its right from the top to the bottom. The four overlap at the corners, and none
 * shares an edge with another along what is seen: no line of the page is left between two of them. Nothing is
 * seen through an empty window.
 */
private fun DrawScope.drawAround(seen: Rect, colour: Color) {
    if (seen.isEmpty) {
        drawRect(colour)
        return
    }
    drawRect(colour, Offset.Zero, Size(size.width, seen.top))
    drawRect(colour, Offset(0f, seen.bottom), Size(size.width, size.height - seen.bottom))
    drawRect(colour, Offset.Zero, Size(seen.left, size.height))
    drawRect(colour, Offset(seen.right, 0f), Size(size.width - seen.right, size.height))
}
