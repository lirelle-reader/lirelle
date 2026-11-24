package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.layout.Corner

/**
 * Where the spreads beside the one shown are drawn: at the reader's zoom and at the corner they open at, or where
 * a tap moved them ahead of a turn.
 *
 * Read panel by panel, a spread that a step turns to may be framed ahead by the stop it is to arrive at: while
 * that turn is on its way it is drawn at that stop, at the stop's own zoom, whatever the reader's. And beside a
 * spread that a stop frames, the others are whole: the reader's zoom is then that stop's, and says nothing of them.
 *
 * @param zoom the reader's zoom, and [pans] the places given to spreads ahead of a turn.
 * @param shown the place of the spread shown.
 * @param turning whether a turn is on its way in a series read panel by panel; never in another.
 */
internal class SpreadBeside(
    private val zoom: SpreadZoom,
    private val pans: SpreadPans,
    private val shown: SpreadPan,
    private val turning: () -> Boolean,
) {
    /** The stop that frames the spread [index] as it slides in; null when it is drawn as any spread beside. */
    fun framed(index: Int): StopFrame? = pans.beside(index)?.framed?.takeIf { turning() }

    /** The zoom the spread [index] is drawn at. */
    fun scale(index: Int): Float = framed(index)?.place?.zoom ?: if (shown.framed != null) MinZoom else zoom.scale

    /**
     * Where the spread [index] stands in its room.
     *
     * @param content its size at the fit, and [corner] the corner it opens at.
     */
    fun offset(index: Int, content: Size, viewport: Size, corner: Corner): Offset =
        framed(index)?.place?.offset
            ?: panBounds(content, viewport, scale(index)).place(pans.beside(index)?.offset, corner)
}
