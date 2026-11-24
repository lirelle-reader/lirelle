package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.SortDirection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ReadAlongFormat = "epub:readalong"

/**
 * Builds the body of a book query (`BookQuery` in the server's types).
 * Linked copies of one work are always folded into one item; series are never folded.
 */
internal fun buildBookQuery(request: BookListRequest, page: Int, size: Int): JsonObject = buildJsonObject {
    val dir = if (request.direction == SortDirection.Ascending) "asc" else "desc"
    put(
        "sort",
        buildJsonArray {
            request.sort.fields.forEach { field ->
                add(
                    buildJsonObject {
                        put("field", field)
                        put("dir", dir)
                    },
                )
            }
        },
    )
    put(
        "pagination",
        buildJsonObject {
            put("page", page)
            put("size", size)
        },
    )
    put("collapseLinked", true)
    request.text?.trim()?.takeIf { it.isNotEmpty() }?.let { put("q", it) }
    val rules = rulesOf(request.filters) + listOfNotNull(request.genre?.let { includesAny("genre", listOf(it)) })
    if (rules.isNotEmpty()) {
        put(
            "filter",
            buildJsonObject {
                put("type", "group")
                put("join", "AND")
                put("rules", JsonArray(rules))
            },
        )
    }
}

/** Format groups are sent in declaration order, so the request does not depend on set order. */
private fun rulesOf(filters: BookFilters): List<JsonObject> = buildList {
    val formats = filters.formats.sortedBy { it.ordinal }.flatMap { it.formats }
    if (formats.isNotEmpty()) add(includesAny("format", formats))
    val statuses = filters.statuses.sortedBy { it.ordinal }.flatMap { it.statuses }
    if (statuses.isNotEmpty()) add(includesAny("readStatus", statuses))
    if (filters.readAlongOnly) add(includesAny("format", listOf(ReadAlongFormat)))
}

private fun includesAny(field: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "rule")
    put("field", field)
    put("operator", "includesAny")
    put("value", JsonArray(values.map(::JsonPrimitive)))
}
