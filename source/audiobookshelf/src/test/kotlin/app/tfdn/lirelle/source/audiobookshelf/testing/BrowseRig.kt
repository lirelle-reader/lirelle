package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.source.audiobookshelf.library.AbsProfileServer
import app.tfdn.lirelle.source.audiobookshelf.library.AbsProgress
import app.tfdn.lirelle.source.audiobookshelf.library.AbsWholeLibrary
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * What the series, authors and shelves of the profile's server on [server] are made with: its way to the server, the
 * account's progress, and the libraries and whole lists the profile keeps, both by the clock [instant], which does
 * not move unless the test moves it.
 */
internal class BrowseRig(server: MockWebServer) {
    var instant: Instant = Instant.EPOCH
    val profileServer: AbsProfileServer = libraryServer(server)
    val progress = AbsProgress(read = { profileServer.remote().api.me().mediaProgress }, now = { instant })
    val whole = AbsWholeLibrary(now = { instant })
}

/** The recorded server's library of books on 2.26.3. */
internal const val LivresIdMinimum = "f1546f4b-c414-4f51-b96f-60765f3eb845"

/** The recorded server's library of books, as recorded on [on]. */
internal fun livresId(on: RecordedOn): String = if (on == RecordedOn.Latest) LivresId else LivresIdMinimum

/** [routes] answer the libraries and the reader's progress recorded on [on]. */
internal fun answerRecordedAccount(routes: LibraryRoutes, on: RecordedOn = RecordedOn.Latest) {
    routes.answer("/api/libraries", fixtureBody("libraries", on))
    routes.answer("/api/me", fixtureBody("me", on))
}

/** The path of a library's series. */
internal fun seriesPath(libraryId: String): String = "/api/libraries/$libraryId/series"

/**
 * What the server answers to a page of a library's series out of [all], as it pages: `limit` and `page` from the
 * request, the total that of [all]; `limit=0` answers no series, as Audiobookshelf does.
 */
internal fun pagedSeries(all: JsonArray): (RecordedRequest) -> MockResponse = { request ->
    val limit = request.url.queryParameter("limit")?.toInt() ?: 0
    val page = request.url.queryParameter("page")?.toInt() ?: 0
    val shown = if (limit == 0) emptyList() else all.drop(page * limit).take(limit)
    routeAnswer(JsonObject(mapOf("results" to JsonArray(shown), "total" to JsonPrimitive(all.size))).toString())
}

/** The series of the recorded list [name] (its `results`), as recorded on [on]. */
internal fun recordedSeries(name: String = "series-livres", on: RecordedOn = RecordedOn.Latest): JsonArray =
    recordedObject(name, on).getValue("results").jsonArray

/** The recorded answer [name], as recorded on [on], as a JSON object. */
internal fun recordedObject(name: String, on: RecordedOn = RecordedOn.Latest): JsonObject =
    Json.parseToJsonElement(fixtureBody(name, on)) as JsonObject

/** This object with [key] set to [value]. */
internal fun JsonObject.with(key: String, value: JsonElement): JsonObject =
    JsonObject(this + (key to value))
