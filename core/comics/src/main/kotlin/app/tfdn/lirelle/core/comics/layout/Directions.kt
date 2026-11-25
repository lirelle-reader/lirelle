package app.tfdn.lirelle.core.comics.layout

import app.tfdn.lirelle.core.comics.settings.Direction

/** A side of the screen. */
enum class Edge { Left, Right }

/** A corner of a page: where the screen stands when the page is larger than it. */
enum class Corner(val top: Boolean, val left: Boolean) {
    TopLeft(top = true, left = true),
    TopRight(top = true, left = false),
    BottomLeft(top = false, left = true),
    BottomRight(top = false, left = false),
}

/** The pages of the spread as they are drawn, from the left of the screen to its right. */
fun Spread.drawn(direction: Direction): List<Int> = when (direction) {
    Direction.LeftToRight, Direction.Vertical -> pages
    Direction.RightToLeft -> pages.reversed()
}

/** The edge of the screen that goes forward, at the end of the reading direction; the other goes back. */
fun Direction.forwardEdge(): Edge = when (this) {
    Direction.LeftToRight, Direction.Vertical -> Edge.Right
    Direction.RightToLeft -> Edge.Left
}

/**
 * Where a page opens. Reached going [forward], at its beginning: its top, at the side where reading
 * starts. Reached going back, at its end: its bottom, at the other side.
 */
fun Direction.openingCorner(forward: Boolean): Corner = when (this) {
    Direction.LeftToRight, Direction.Vertical -> if (forward) Corner.TopLeft else Corner.BottomRight
    Direction.RightToLeft -> if (forward) Corner.TopRight else Corner.BottomLeft
}

/**
 * The direction pages are turned in, for what knows only pages turned (the panels of a page): an album read
 * vertically has none, and counts as read from the left.
 */
fun Direction.sideways(): Direction = if (this == Direction.Vertical) Direction.LeftToRight else this
