// Every search and sort said to Komga stays in this one file (see below), one small function each.
@file:Suppress("TooManyFunctions")

package app.tfdn.lirelle.source.komga.library

import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// How the app's lists are said to Komga, all in this file so that a word Komga reads otherwise is changed in one
// place. A search is `{"condition": {"allOf": [...]}, "fullTextSearch": "..."}`; each condition is
// `{"<field>": {"operator": "...", "value": "..."}}`, several values of one field `{"anyOf": [...]}`. The order is
// the `sort` query: `property,asc|desc`, one per key.

/** The series sorted by title, as the app sorts them again on the device. */
internal const val SeriesOrder = "metadata.titleSort,asc"

/** The books of a series in their volumes' order. */
internal const val VolumeOrder = "metadata.numberSort,asc"

/**
 * The books of a read list in its own order (to confirm on a real server: Komga ignores a sort key it does not
 * know), when the user keeps the list's default sort.
 */
internal const val ReadListOrder = "readList.number,asc"

/** The books being read, the most recently read first, as Komga's web interface finds them (no route of its own). */
internal const val ContinueOrder = "readProgress.readDate,desc"

/** The search of Home's "Continue reading": the books in progress, still in the library. */
internal fun continueSearch(): JsonObject =
    bookSearch(BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Reading))))

/** How many series and how many books Search shows first, as for BookOrbit; "See all" lists the rest. */
internal const val SearchSectionSize = 10

/** The search of the history: the books read, still in the library. */
internal fun historySearch(): JsonObject =
    bookSearch(BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Read))))

/**
 * The history's order: by the date each book was read, the most recent first when [newestFirst], then by series and
 * by volume in the same direction, so that books marked read together keep one order from a page to the next.
 */
internal fun historyOrder(newestFirst: Boolean): List<String> {
    val direction = if (newestFirst) "desc" else "asc"
    return listOf("readProgress.readDate,$direction", "series,$direction", "metadata.numberSort,$direction")
}

/** How many books are asked for a row of Home: the row itself, or its "See all" screen. */
internal val HomeRowExtent.size: Int
    get() = when (this) {
        HomeRowExtent.Row -> HomeRowSize
        HomeRowExtent.SeeAll -> SeeAllSize
    }

private const val HomeRowSize = 20
private const val SeeAllSize = 50

/** The search of the books [request] asks, those of [seriesIds] only when given (the series of a genre). */
internal fun bookSearch(request: BookListRequest, seriesIds: List<String>? = null): JsonObject {
    val scope = seriesIds?.let { ids -> listOfNotNull(anyOf(ids.map { isValue("seriesId", it) })) }.orEmpty()
    return booksMatching(scope, request)
}

/** The search of the books of the read list [readListId] that [request] asks, narrowed as every list of books. */
internal fun readListBookSearch(readListId: String, request: BookListRequest): JsonObject =
    booksMatching(listOf(isValue("readListId", readListId)), request)

private fun booksMatching(scope: List<JsonObject>, request: BookListRequest): JsonObject {
    val statuses = request.filters.statuses.sortedBy { it.ordinal }.mapNotNull { it.readStatus }
    val profiles = request.filters.formats.sortedBy { it.ordinal }.mapNotNull { it.mediaProfile }
    val conditions = scope + NotDeleted + listOfNotNull(
        anyOf(statuses.map { isValue("readStatus", it) }),
        anyOf(profiles.map { isValue("mediaProfile", it) }),
    )
    return search(conditions, request.text)
}

/** The books of the series [seriesId] still in the library. */
internal fun seriesBooksSearch(seriesId: String): JsonObject =
    search(listOf(isValue("seriesId", seriesId), NotDeleted), text = null)

/** The series still in the library, matching [text] and of [genre] when given. */
internal fun seriesSearch(text: String?, genre: String? = null): JsonObject =
    search(listOfNotNull(NotDeleted, genre?.let { isValue("genre", it) }), text)

/** Komga's sort keys for the app's [sort], most significant first. */
internal fun bookSorts(sort: BookSort, direction: SortDirection): List<String> {
    val order = if (direction == SortDirection.Ascending) "asc" else "desc"
    val keys = when (sort) {
        BookSort.Added -> listOf("createdDate")
        // Komga's books have no author order (not offered): by title rather than an error.
        BookSort.Title, BookSort.Author -> listOf("metadata.title")
        BookSort.Series -> listOf("series", "metadata.numberSort")
        BookSort.Published -> listOf("metadata.releaseDate")
    }
    return keys.map { "$it,$order" }
}

/**
 * Komga's sort keys for a read list's books: the read list's own order while the user keeps the default sort (which
 * cannot be told from the same sort chosen), else the sort chosen, as for every list of books.
 */
internal fun readListSorts(sort: BookSort, direction: SortDirection): List<String> {
    val default = BookListRequest()
    return if (sort == default.sort && direction == default.direction) {
        listOf(ReadListOrder)
    } else {
        bookSorts(sort, direction)
    }
}

/** Komga's read status for a reading state; null for those it does not have. */
private val StatusFilter.readStatus: String?
    get() = when (this) {
        StatusFilter.Unread -> "UNREAD"
        StatusFilter.Reading -> "IN_PROGRESS"
        StatusFilter.Read -> "READ"
        StatusFilter.WantToRead, StatusFilter.Abandoned -> null
    }

/** Komga's media profile for a group of formats: comics are its books of images (CBZ, CBR…); no audio. */
private val FormatFilter.mediaProfile: String?
    get() = when (this) {
        FormatFilter.Epub -> "EPUB"
        FormatFilter.Pdf -> "PDF"
        FormatFilter.Comics -> "DIVINA"
        FormatFilter.Audio -> null
    }

/** A book or a series in the trash is never listed: its file is gone. */
private val NotDeleted: JsonObject = buildJsonObject {
    put("deleted", buildJsonObject { put("operator", "isFalse") })
}

private fun isValue(field: String, value: String): JsonObject = buildJsonObject {
    put(
        field,
        buildJsonObject {
            put("operator", "is")
            put("value", value)
        },
    )
}

/** Any one of [conditions]: the condition itself when there is one, none when there is none. */
private fun anyOf(conditions: List<JsonObject>): JsonObject? = when (conditions.size) {
    0 -> null
    1 -> conditions.single()
    else -> buildJsonObject { put("anyOf", JsonArray(conditions)) }
}

/** [text], trimmed and taken as text, is the full-text search; a blank one is none. */
private fun search(conditions: List<JsonObject>, text: String?): JsonObject = buildJsonObject {
    put("condition", buildJsonObject { put("allOf", JsonArray(conditions)) })
    text?.trim()?.takeIf { it.isNotEmpty() }?.let { put("fullTextSearch", luceneText(it)) }
}

/** Lucene's reserved characters, each of which a backslash makes plain text (as its `QueryParser.escape`). */
private val LuceneReserved = setOf(
    '\\', '+', '-', '!', '(', ')', ':', '^', '[', ']', '"', '{', '}', '~', '*', '?', '|', '&', '/',
)

/** Lucene's operators, words of their own in capitals; in lower case they are words to find, as Komga folds case. */
private val LuceneOperator = Regex("""(?<!\S)(AND|OR|NOT)(?!\S)""")

/**
 * The full-text search is a Lucene query: what the user typed is searched as text, each reserved character escaped
 * and each operator lowered, rather than read as a query (or refused as a broken one).
 */
private fun luceneText(text: String): String {
    val escaped = buildString {
        text.forEach { char ->
            if (char in LuceneReserved) append('\\')
            append(char)
        }
    }
    return LuceneOperator.replace(escaped) { it.value.lowercase() }
}
