package app.tfdn.lirelle.source.kavita.testing

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.source.kavita.library.KavitaLibrary
import app.tfdn.lirelle.source.kavita.library.KavitaLibraryApi
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.network.KavitaKeyAuthorizer
import app.tfdn.lirelle.source.kavita.reading.KavitaReader
import app.tfdn.lirelle.source.kavita.reading.KavitaReadingApi
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * A Kavita server that answers by route (`GET /api/Chapter`), whatever the order the library asks in: what is
 * asked in parallel comes in any order. A route without an answer is not found (404); the last answer given to a
 * route is the one it gets.
 */
internal class KavitaRoutes(private val server: MockWebServer) {

    private val answers = CopyOnWriteArrayList<Pair<String, (RecordedRequest) -> MockResponse>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                answers.lastOrNull { it.first == request.route() }?.second?.invoke(request) ?: MockResponse(code = 404)
        }
    }

    /** [route] answers what [respond] makes of each request. */
    fun answer(route: String, respond: (RecordedRequest) -> MockResponse) {
        answers += route to respond
    }

    /** [route] answers [body], with [headers]. */
    fun answer(route: String, body: String, vararg headers: Pair<String, String>) =
        answer(route) { json(body, headers = headers) }

    /** [route] answers the recorded answer [name]: its body, with its `Pagination` header when it had one. */
    fun answerFixture(route: String, name: String, version: RecordedOn = RecordedOn.Latest) {
        val pagination = fixturePagination(name, version)
        val headers = listOfNotNull(pagination?.let { "Pagination" to it }).toTypedArray()
        answer(route, fixtureBody(name, version), *headers)
    }

    /** [route] answers [code] and no body. */
    fun fail(route: String, code: Int) = answer(route) { MockResponse(code = code) }

    /** Every request the server had, in the order they came. */
    fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    /**
     * A Kavita profile's library on this server, asked with the profile's key as the authenticated client is, its
     * time told by [clock]. The client makes up to [ParallelCalls] calls at once, more than the library's own limit,
     * so that the limit seen is the library's.
     */
    fun library(
        session: FakeProfileSession = FakeProfileSession(kavitaSession(server.serverUrl)),
        clock: () -> Long = { 0L },
    ): KavitaLibrary = KavitaLibrary(
        session,
        KavitaApis(client(session), KavitaJson, KavitaLibraryApi::class.java),
        FakeTitles,
        KavitaErrors(),
        clock,
    )

    /**
     * The routes of reading, asked as the profile's client asks them: with the profile's key as the header, and in
     * the address of the routes that take it there (page images, PDF files), so that `apiKey` shows in the requests
     * the server records.
     */
    fun readingApi(
        session: FakeProfileSession = FakeProfileSession(kavitaSession(server.serverUrl)),
    ): KavitaApis<KavitaReadingApi> =
        KavitaApis(client(session), KavitaJson, KavitaReadingApi::class.java)

    /** A Kavita profile's reading on this server, asked as the profile's client asks, its time told by [now]. */
    fun reader(
        session: FakeProfileSession = FakeProfileSession(kavitaSession(server.serverUrl)),
        now: () -> Instant = { Instant.EPOCH },
    ): KavitaReader = KavitaReader(
        session,
        readingApi(session),
        KavitaApis(client(session), KavitaJson, KavitaLibraryApi::class.java),
        FakeTitles,
        KavitaErrors(),
        now,
    )

    /** The server's address in the form the profile keeps it: no trailing slash. */
    val serverUrl: String get() = server.serverUrl

    /** The address of [path] (`/api/Reader/image`) on this server, with [query] when given (`chapterId=44`). */
    fun urlOf(path: String, query: String? = null): String =
        serverUrl + path + query?.let { "?$it" }.orEmpty()

    /** The client of a profile with [session]: [ParallelCalls] calls at once, its key given to what it asks. */
    private fun client(session: FakeProfileSession): OkHttpClient {
        val calls = okhttp3.Dispatcher().apply {
            maxRequests = ParallelCalls
            maxRequestsPerHost = ParallelCalls
        }
        return OkHttpClient.Builder()
            .dispatcher(calls)
            .addInterceptor(keyed(KavitaKeyAuthorizer(session)))
            .build()
    }

    /** The profile's client, as far as its calls are concerned: what the authorizer gives goes with the request. */
    private fun keyed(authorizer: KavitaKeyAuthorizer) = Interceptor { chain ->
        val header: AuthHeader? = runBlocking { authorizer.header(chain.request().url) }
        val request = chain.request().newBuilder().url(authorizer.address(chain.request().url))
        header?.let { request.header(it.name, it.value) }
        chain.proceed(request.build())
    }
}

/** How many calls the test client makes at once. */
private const val ParallelCalls = 32

/** A JSON answer. */
internal fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
    val all = listOf("Content-Type" to "application/json") + headers
    return MockResponse(code = code, headers = headersOf(*all.flatMap { it.toList() }.toTypedArray()), body = body)
}

/** The request's method and path: `GET /api/Chapter`. */
internal fun RecordedRequest.route(): String = "$method ${url.encodedPath}"

/** The request's JSON body. */
internal fun RecordedRequest.jsonBody(): JsonElement = Json.parseToJsonElement(bodyText())

/** The recorded answer [name], an object, with [fields] changed. */
internal fun fixtureWith(name: String, vararg fields: Pair<String, Number>): String {
    val recorded = Json.parseToJsonElement(fixtureBody(name)) as JsonObject
    return JsonObject(recorded + fields.map { (key, value) -> key to JsonPrimitive(value) }).toString()
}
