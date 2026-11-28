package app.tfdn.lirelle.source.audiobookshelf.testing

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

/**
 * An Audiobookshelf server that answers by path (`/api/libraries`), whatever the order the library asks in. A path
 * without an answer is not found (404); the last answer given to a path is the one it gets. Every request is kept,
 * in the order it came.
 */
internal class LibraryRoutes(server: MockWebServer) {

    private val answers = CopyOnWriteArrayList<Pair<String, (RecordedRequest) -> MockResponse>>()
    private val asked = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += request
                return answers.lastOrNull { it.first == request.url.encodedPath }?.second?.invoke(request)
                    ?: MockResponse(code = 404)
            }
        }
    }

    /** [path] answers what [respond] makes of each request. */
    fun answer(path: String, respond: (RecordedRequest) -> MockResponse) {
        answers += path to respond
    }

    /** [path] answers [body]. */
    fun answer(path: String, body: String) = answer(path) { routeAnswer(body) }

    /** [path] answers [code] and a word, as Audiobookshelf's refusals are. */
    fun fail(path: String, code: Int) = answer(path) { MockResponse(code = code, body = "Forbidden") }

    /** Every request the server had, in the order they came. */
    fun requests(): List<RecordedRequest> = asked.toList()

    /** The requests the server had for [path]. */
    fun requests(path: String): List<RecordedRequest> = asked.filter { it.url.encodedPath == path }
}

/** A JSON answer of a route. */
internal fun routeAnswer(body: String): MockResponse =
    MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)

/** The path of a library, which answers its filter data (`include=filterdata`). */
internal fun libraryPath(libraryId: String): String = "/api/libraries/$libraryId"

/** The path of a library's items. */
internal fun itemsPath(libraryId: String): String = "/api/libraries/$libraryId/items"

/**
 * What the server answers to a page of a library's items out of the recorded list [name], as it pages: `limit` and
 * `page` from the request, `limit=0` for the whole list, the total that of the whole list.
 */
internal fun pagedItems(name: String, on: RecordedOn = RecordedOn.Latest): (RecordedRequest) -> MockResponse {
    val all = (Json.parseToJsonElement(fixtureBody(name, on)) as JsonObject).getValue("results").jsonArray
    return { request ->
        val limit = request.url.queryParameter("limit")?.toInt() ?: 0
        val page = request.url.queryParameter("page")?.toInt() ?: 0
        val shown = if (limit == 0) all else all.drop(page * limit).take(limit)
        val answer = JsonObject(mapOf("results" to JsonArray(shown), "total" to JsonPrimitive(all.size)))
        routeAnswer(answer.toString())
    }
}
