package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A spread in the screen, with the place the user gave it. */
internal class PlacedSpread(val pan: SpreadPan, val frame: SpreadFrame)

/**
 * The places the user gave to the spreads: to the one shown, and to the one a turn on its way leads to, when a
 * tap already moved it, or when a step of a reader that reads panel by panel framed it by the stop it is to arrive
 * at. That one keeps its place when the reader shows it; any other spread opens at its corner.
 */
@Stable
internal class SpreadPans {
    private var ahead: Pair<Int, SpreadPan>? by mutableStateOf(null)

    /** The place of the spread [index], which the reader now shows. */
    fun shown(index: Int): SpreadPan {
        val pan = beside(index) ?: SpreadPan()
        ahead = null
        return pan
    }

    /** The place of the spread [index], which a turn on its way leads to. */
    fun ahead(index: Int): SpreadPan = beside(index) ?: SpreadPan().also { ahead = index to it }

    /** The place a tap gave to the spread [index] before the reader shows it, if one did. */
    fun beside(index: Int): SpreadPan? = ahead?.takeIf { it.first == index }?.second

    /** No turn is on its way: what a tap moved ahead of one the reader did not take is lost. */
    fun forget() {
        ahead?.second?.scrolling?.cancel()
        ahead = null
    }
}

/**
 * The spreads a tap on an edge may move.
 *
 * @property shown the spread the reader shows.
 * @param index its index, and [frameOf] the frame of another spread of the album, reached going forward or
 * back; null where the album has none.
 */
internal class SpreadPlaces(
    val shown: PlacedSpread,
    private val pans: SpreadPans,
    private val index: Int,
    private val frameOf: (index: Int, forward: Boolean) -> SpreadFrame?,
) {
    /** The spread the [turns] on their way lead to (back under zero), as it will open. */
    fun ahead(turns: Int): PlacedSpread? =
        frameOf(index + turns, turns > 0)?.let { PlacedSpread(pans.ahead(index + turns), it) }

    fun forget() = pans.forget()
}
