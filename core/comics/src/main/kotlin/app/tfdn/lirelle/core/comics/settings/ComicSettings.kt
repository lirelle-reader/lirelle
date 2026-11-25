package app.tfdn.lirelle.core.comics.settings

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId

/** How a page fills the screen. */
enum class Fit {
    /** The whole page inside the screen. */
    Page,

    /** The page as wide as the screen, scrolling down. */
    Width,
}

/** When two pages show side by side. */
enum class TwoPages {
    /** On a tablet in landscape only. */
    Automatic,

    /** In any window wider than it is high. */
    Always,
    Never,
}

/**
 * The way the album is read: where a page starts, and which way the next one is. [Vertical] is no page turned:
 * the pages follow one another down the screen, which scrolls (a webtoon).
 */
enum class Direction { LeftToRight, RightToLeft, Vertical }

/** The colour around the pages. */
enum class Background { Black, Grey, White }

/**
 * How comics are shown: [direction] and [panels] are the series's, or the album's when it has no series; the
 * rest is the device's.
 *
 * @property panels whether the series is read panel by panel; the series's, or the album's when it has no series.
 * @property wholePageFirst whether, panel by panel, each page is shown whole before its first panel; the device's.
 */
data class ComicSettings(
    val fit: Fit = Fit.Page,
    val twoPages: TwoPages = TwoPages.Automatic,
    val direction: Direction = Direction.LeftToRight,
    val background: Background = Background.Black,
    val panels: Boolean = false,
    val wholePageFirst: Boolean = true,
)

/** What a reading direction, and reading panel by panel, are remembered for. */
sealed interface DirectionScope {
    data class Series(val id: SeriesId) : DirectionScope

    /** An album that belongs to no series. */
    data class Book(val id: BookId) : DirectionScope
}
