package app.tfdn.lirelle.core.comics.pdf

/** A page to draw for [longSide] pixels on its long side. */
internal data class Drawing(val index: Int, val longSide: Int)

/**
 * What is to be drawn, one page at a time: the page the reader is on first, then the pages it zoomed on,
 * then the window around it, in its order.
 *
 * @param shown the page the reader arrived on last; one passed before it is drawn only if the window has it.
 * @param sharper the pages to draw again larger, in the order they were asked.
 * @param waiting the pages of the window not drawn yet.
 */
internal data class DrawPlan(
    val shown: Int? = null,
    val sharper: List<Drawing> = emptyList(),
    val waiting: List<Int> = emptyList(),
) {
    /** The drawing to make now, or null when there is none. */
    fun next(): Drawing? = shown?.let(::first) ?: sharper.firstOrNull() ?: waiting.firstOrNull()?.let(::first)

    /** The [drawing] is being made: the page waits for nothing that it gives. */
    fun started(drawing: Drawing): DrawPlan = copy(
        shown = shown.takeIf { it != drawing.index },
        sharper = sharper.filterNot { it.index == drawing.index && it.longSide <= drawing.longSide },
        waiting = waiting - drawing.index,
    )

    fun shown(index: Int): DrawPlan = copy(shown = index)

    /** The [window] of the page the reader stayed on replaces the one that waited. */
    fun settled(window: List<Int>): DrawPlan = copy(waiting = window)

    /** A page asked sharper again is drawn once, for the size asked last. */
    fun sharpened(drawing: Drawing): DrawPlan =
        copy(sharper = sharper.filterNot { it.index == drawing.index } + drawing)

    private fun first(index: Int) = Drawing(index, FirstLongSidePx)
}
