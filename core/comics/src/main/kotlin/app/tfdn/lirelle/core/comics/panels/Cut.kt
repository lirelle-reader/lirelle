package app.tfdn.lirelle.core.comics.panels

/**
 * The constants of the cut. They are the prototype's, tuned on the measured libraries: change one, change
 * [CutVersion].
 */
internal object Cut {
    /** How far a channel may be from the gutter colour. */
    const val Tolerance = 40

    /** The background must be at least this wide to carry the flood: stops leaks through broken frames. */
    const val Leak = 3

    /** A blob this thin (share of the long side) … */
    const val SpeckSide = 0.025

    /** … and this small (share of the page) is text lying in a gutter, not a panel. */
    const val SpeckArea = 0.008

    /** Thinner than this (share of the long side) is not a part of a panel … */
    const val Opening = 0.016

    /** … and so is what is thinner than this, in pixels, on a small picture. */
    const val OpeningLeast = 3

    /** A line shared by two panels: this share of its length is a thin stroke … */
    const val LineShare = 0.90

    /** … darker or lighter than the pixels this far on both sides … */
    const val StrokeReach = 6

    /** … by this many grey levels. */
    const val StrokeContrast = 30

    /** The whole separator is at most this thick (share of the long side) … */
    const val LineThickness = 0.012

    /** … and the lines a few pixels away on both sides are separators on less than this share. */
    const val LineFlank = 0.5

    /** A shared line is at least this far (share of the page height) from its zone's top and bottom … */
    const val LineEdgeAcross = 0.10

    /** … or this far (share of the page width) from its sides: a caption strip is not split from its panel. */
    const val LineEdgeAlong = 0.08

    /** A line is a gutter line when this share of it is gutter … */
    const val GutterShare = 0.90

    /** … and its longest blocked span is at most this share of it (a balloon across the gutter). */
    const val Blocked = 0.06

    /** A gutter may wander this much (share of the long side) off a straight line. */
    const val Wander = 0.006

    /** The least thickness of a gutter, in pixels. */
    const val GutterThickness = 3

    /** A line belongs to a zone when more than this share of it is not gutter. */
    const val TrimShare = 0.02

    /** Zones smaller than this (share of a page side) are dropped: page numbers, footnotes. */
    const val SmallSide = 0.04

    /** … or than this share of the page. */
    const val SmallArea = 0.008

    /** A zone whose box is filled this much is one panel. */
    const val PanelFill = 0.90

    /** The zones of a page that is cut take together at least this share of it: under it, it is a title page. */
    const val LeastCover = 0.40

    /** … and at least this share of the box around them all: under it, they are pieces on a ground. */
    const val LeastTiling = 0.65

    /** How deep the cut goes. */
    const val Depth = 10

    /** The border band the gutter colour is read from (share of the short side) … */
    const val Band = 0.02

    /** … and its least width in pixels. */
    const val BandLeast = 2

    /** The pad of gutter put around the page before the flood, in pixels. */
    const val Pad = 4
}
