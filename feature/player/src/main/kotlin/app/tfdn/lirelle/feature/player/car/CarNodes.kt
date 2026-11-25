package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.MediaProfile

private const val MillisPerSecond = 1_000L

/** A place in what the car browses. */
sealed interface CarPlace {
    data object Root : CarPlace

    /** The tabs of the root, in the order the car shows them. */
    enum class Tab : CarPlace { Home, Downloaded, Audiobooks, Series }

    /** The books of one series that can be listened to. */
    data class OneSeries(val id: SeriesId) : CarPlace
}

/** What the car shows of a tab, a book or a series. */
sealed interface CarNode {
    data class Tab(val tab: CarPlace.Tab) : CarNode

    /** @property section the group the book stands in, in the Home tab; null anywhere else. */
    data class Book(
        val id: BookId,
        val title: String,
        val line: CarLine?,
        val section: CarSection? = null,
    ) : CarNode

    /** @property books how many of its books can be listened to. */
    data class Series(val id: SeriesId, val name: String, val books: Int) : CarNode
}

/**
 * The groups of the Home tab, in the order the car shows them: the rows of the phone's Home that can hold
 * something to listen to.
 *
 * @property row the row of Home it is made of.
 */
enum class CarSection(val row: HomeRowKind) {
    Continue(HomeRowKind.Continue),
    UpNext(HomeRowKind.UpNext),
    RecentlyAdded(HomeRowKind.RecentlyAdded),
}

/** The line under a book's title: what is left of it, its series and number, or its authors. */
sealed interface CarLine {
    /** @property millis the listening time left, at normal speed. */
    data class Left(val millis: Long) : CarLine

    /** @property index the book's number in the series, as its server writes it; null when it has none. */
    data class InSeries(val name: String, val index: String?) : CarLine

    /** @property name the book's authors, in one line. */
    data class Author(val name: String) : CarLine
}

/** Why a place has nothing to list. */
enum class CarNotice {
    /** The profile's source has neither audiobooks nor read-aloud books. */
    NothingToListen,

    /** The profile waits for a sign-in on the phone: only what the device keeps can be listed. */
    SignInNeeded,

    /** The server did not answer, or answered a failure: the car has one way to say it. */
    Unreachable,
}

/** What a place answers: what it lists, or why it lists nothing. */
sealed interface CarAnswer {
    data class Nodes(val nodes: List<CarNode>) : CarAnswer

    data class Notice(val notice: CarNotice) : CarAnswer
}

/**
 * Whether the player can play [summary] for a profile whose source has [capabilities]: its audiobook, or the
 * narration of its read-aloud text.
 */
fun listenable(summary: BookSummary, capabilities: SourceCapabilities): Boolean =
    capabilities.audiobooks && summary.hasAudioFiles ||
        capabilities.readAlong && summary.media == MediaProfile.ReadAlong

/** Whether the source has anything the player plays. */
internal val SourceCapabilities.hasListening: Boolean get() = audiobooks || readAlong

/** The book as the car shows it, under [line]. */
internal fun BookSummary.carBook(line: CarLine?, section: CarSection? = null): CarNode.Book =
    CarNode.Book(id, title, line, section)

/** Null for a book without an author: the car then shows its title alone. */
internal fun authorLine(authors: List<String>): CarLine? =
    authors.takeIf { it.isNotEmpty() }?.let { CarLine.Author(it.joinToString(", ")) }

/** The book's series and number; its authors for a book outside a series. */
internal fun BookSummary.seriesLine(): CarLine? =
    series?.let { CarLine.InSeries(it.name, it.index) } ?: authorLine(authors)

/**
 * What a book of [section] says under its title: a book to continue how much is left to listen to, when its card
 * knows (a server's card does not: the phone's Home asks each book's length for it), a next volume its series
 * and number, any other its authors.
 */
internal fun BookSummary.lineIn(section: CarSection): CarLine? = when (section) {
    CarSection.Continue -> timeLeftSeconds?.let { CarLine.Left(it * MillisPerSecond) } ?: authorLine(authors)
    CarSection.UpNext -> seriesLine()
    CarSection.RecentlyAdded -> authorLine(authors)
}

/** Page [page], numbered from 0, of a list cut into pages of [pageSize]; empty past the end. */
internal fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
    // In Long: a car may ask for a page of any size.
    val from = page.toLong() * pageSize
    if (page < 0 || pageSize <= 0 || from >= size) return emptyList()
    return subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt())
}
