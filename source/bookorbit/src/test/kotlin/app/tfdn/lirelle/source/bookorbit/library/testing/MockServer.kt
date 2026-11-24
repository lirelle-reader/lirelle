package app.tfdn.lirelle.source.bookorbit.library.testing

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

val MockWebServer.serverUrl: String get() = url("/").toString().trimEnd('/')

private const val NotFound = 404
private const val ServerError = 500

private fun jsonResponse(body: String, code: Int = 200) =
    MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body)

fun MockWebServer.enqueueJson(body: String, code: Int = 200) {
    enqueue(jsonResponse(body, code))
}

/**
 * Answers the JSON body of the first key contained in the request path and query (for example
 * `continue-reading` or `reading-progress?bookId=2`), and 404 otherwise. A request containing
 * one of [failing] is answered 500. For calls that run concurrently, where a queue would
 * depend on their order.
 */
fun dispatcherOf(vararg answers: Pair<String, String>, failing: List<String> = emptyList()): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val target = request.target()
            if (failing.any { it in target }) return MockResponse(code = ServerError)
            val body = answers.firstOrNull { (key, _) -> key in target }?.second
            return body?.let(::jsonResponse) ?: MockResponse(code = NotFound)
        }
    }

fun MockWebServer.enqueueRecorded(name: String) = enqueueJson(Fixtures.recorded(name))

fun RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

/** Path with its query, for example `/api/v1/series?page=0&size=100`. */
fun RecordedRequest.target(): String = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
