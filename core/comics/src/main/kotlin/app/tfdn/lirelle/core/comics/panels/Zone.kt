package app.tfdn.lirelle.core.comics.panels

/** The long side, in pixels, of the picture a page is cut on. Larger gains nothing; smaller loses thin gutters. */
internal const val WorkingSide = 1600

/** The version of the cut: zones kept under another one are not used. Raised whenever the answers may change. */
internal const val CutVersion = 4

/** A page's picture as the cut reads it: [argb] row by row, [width] by [height]. */
internal class PagePixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "pixels do not match the size" }
    }
}

private const val RedShift = 16
private const val GreenShift = 8
private const val Channel = 0xFF

/** The red of a pixel of [PagePixels.argb], from 0 to 255; [green] and [blue] likewise. */
internal fun red(argb: Int): Int = argb shr RedShift and Channel

internal fun green(argb: Int): Int = argb shr GreenShift and Channel

internal fun blue(argb: Int): Int = argb and Channel

/**
 * How filled the box of a zone is, and no more: it decides nothing about whether the page is cut, nor about how a
 * zone is read.
 */
enum class ZoneKind {
    /** The zone's box is filled: most often one panel, or a row left whole that is well filled. */
    Filled,

    /**
     * The zone's box is sparsely filled: art without frames, a panel with pieces around it. It may be a true panel
     * all the same: on a dark page, or where balloons open on the gutter, the gutter seems to enter the panels,
     * and every zone of a page that is cut may be of this kind.
     */
    Sparse,
}

/**
 * A zone of a page, in proportions of the page (0 to 1, from its left and its top): they hold for the picture the
 * server gives, for the one the cut worked on and for the copy the device keeps, whatever their sizes.
 */
data class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float, val kind: ZoneKind)
