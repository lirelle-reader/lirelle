package app.tfdn.lirelle.source.bookorbit.network.testing

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

/** Server address in the form the app stores it: no trailing slash. */
val MockWebServer.serverUrl: String get() = url("/").toString().trimEnd('/')

fun MockWebServer.enqueueJson(body: String, code: Int = 200) {
    enqueue(MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body))
}

fun MockWebServer.enqueueFixture(name: String, code: Int = 200) = enqueueJson(Fixtures.read(name), code)

fun RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

/** Path and query as sent, for example `/api/v1/auth/me`. */
fun RecordedRequest.pathText(): String = url.encodedPath
