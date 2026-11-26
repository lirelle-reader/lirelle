package app.tfdn.lirelle.source.kavita.library

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The bodies of Kavita's filtered lists (`POST api/Series/all-v2`, `api/want-to-read/v2`, `api/Person/all`). A
// field and a comparison that do not go together make the server answer 500: only the pairs tried on the test
// server are used — library and Equal, name and Matches, genres, formats, writers and penciller with Contains.

/** The fields a series is filtered on. */
internal object SeriesField {
    const val Name = 1
    const val Penciller = 16
    const val Writers = 17
    const val Genres = 18
    const val Libraries = 19
    const val Formats = 21
}

/** How a statement's value is compared: Contains takes a comma-separated list of ids, Matches a piece of text. */
internal object Comparison {
    const val Equal = 0
    const val Contains = 5
    const val Matches = 7
}

/** The orders of a list of series. */
internal object SeriesSortField {
    const val SortName = 1
    const val CreatedDate = 2
    const val LastChapterAdded = 4
}

/** The fields a person is filtered on. */
internal object PersonField {
    const val Role = 1
    const val Name = 2
}

/** The orders of a list of people. */
internal object PersonSortField {
    const val Name = 1
    const val ChapterCount = 3
}

/** The roles of the people the app shows as authors, Writer and Penciller: Kavita has no Artist role. */
internal const val WriterAndPenciller = "3,4"

/** One condition of a filter: [field] compared to [value] by [comparison]. */
internal data class Statement(val field: Int, val comparison: Int, val value: String)

/** A filter of series: all its [statements] hold, or any of them when [or]; sorted by [sortField]. */
internal fun seriesFilter(
    statements: List<Statement>,
    or: Boolean = false,
    sortField: Int = SeriesSortField.SortName,
    ascending: Boolean = true,
): JsonObject = filter(statements, or, sortField, ascending)

/** A filter of people: all its [statements] hold, or any of them when [or]; sorted by [sortField]. */
internal fun personFilter(
    statements: List<Statement>,
    or: Boolean = false,
    sortField: Int = PersonSortField.Name,
    ascending: Boolean = true,
): JsonObject = filter(statements, or, sortField, ascending)

/** Series and people are filtered with bodies of the same shape; no limit on the number of items. */
private fun filter(statements: List<Statement>, or: Boolean, sortField: Int, ascending: Boolean) = buildJsonObject {
    putJsonArray("statements") {
        statements.forEach {
            addJsonObject {
                put("comparison", it.comparison)
                put("field", it.field)
                put("value", it.value)
            }
        }
    }
    put("combination", if (or) CombinationOr else CombinationAnd)
    putJsonObject("sortOptions") {
        put("sortField", sortField)
        put("isAscending", ascending)
    }
    put("limitTo", NoLimit)
}

private const val CombinationOr = 0
private const val CombinationAnd = 1
private const val NoLimit = 0
