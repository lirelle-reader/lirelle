package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.StatusFilter

/** BookOrbit's sort fields for a sort of books, most significant first. */
internal val BookSort.fields: List<String>
    get() = when (this) {
        BookSort.Added -> listOf("addedAt")
        BookSort.Title -> listOf("title")
        BookSort.Author -> listOf("author")
        BookSort.Series -> listOf("series", "seriesIndex")
        BookSort.Published -> listOf("publishedDate")
    }

/** BookOrbit's format values a group of formats stands for. */
internal val FormatFilter.formats: List<String>
    get() = when (this) {
        FormatFilter.Epub -> listOf("epub", "kepub")
        FormatFilter.Pdf -> listOf("pdf")
        FormatFilter.Comics -> listOf("cbz", "cbr", "cb7")
        FormatFilter.Audio -> listOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
    }

/** BookOrbit's reading statuses a reading state stands for (it has eight, the app five). */
internal val StatusFilter.statuses: List<String>
    get() = when (this) {
        StatusFilter.Unread -> listOf("unread")
        StatusFilter.WantToRead -> listOf("want_to_read")
        StatusFilter.Reading -> listOf("reading", "rereading", "on_hold")
        StatusFilter.Read -> listOf("read", "skimmed")
        StatusFilter.Abandoned -> listOf("abandoned")
    }

/** BookOrbit's sort field for a sort of authors. */
internal val AuthorSort.field: String
    get() = when (this) {
        AuthorSort.Name -> "sortName"
        AuthorSort.BookCount -> "bookCount"
    }

/** BookOrbit's dashboard rows ("scrollers") that feed a row of Home. */
internal val HomeRowKind.scrollers: List<String>
    get() = when (this) {
        HomeRowKind.Continue -> listOf(ContinueReading, ContinueListening)
        HomeRowKind.WantToRead -> listOf("want-to-read")
        HomeRowKind.UpNext -> listOf("up-next-in-series")
        HomeRowKind.RecentlyAdded -> listOf("recently-added")
    }

/** How many books are asked for a row of Home. */
internal val HomeRowExtent.limit: Int
    get() = when (this) {
        HomeRowExtent.Row -> RowLimit
        HomeRowExtent.SeeAll -> SeeAllLimit
    }

private const val RowLimit = 20

/** The most BookOrbit returns for a dashboard row. */
private const val SeeAllLimit = 50

private const val ContinueReading = "continue-reading"

/** The dashboard row of the books being listened to, one of those the "Continue" row merges. */
internal const val ContinueListening = "continue-listening"
