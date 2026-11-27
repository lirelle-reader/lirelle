package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.core.session.PendingOidcStore
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

/** The cookies Audiobookshelf sets on the first call of a sign-in through its provider, as 2.38 sets them. */
val TheSetCookies = listOf(
    "auth_method=openid-mobile; Max-Age=315360000; Path=/; HttpOnly",
    "connect.sid=s%3Asess-1.signature; Path=/; HttpOnly",
)

/** What goes back to the server with the code: the two cookies, names and values only. */
const val TheCookieHeader = "auth_method=openid-mobile; connect.sid=s%3Asess-1.signature"

/**
 * Audiobookshelf's routes of a sign-in through its provider, as 2.38 answers them. The first call
 * (`GET auth/openid`) is sent to [location] of the state it was given — the provider's page, which echoes it —
 * with [setCookies], when its `redirect_uri` is one of [allowed], and refused (400 `Invalid redirect_uri`)
 * otherwise; [firstStatus] answers it instead when set. A [location] of null: a 302 without one. The exchange
 * (`GET auth/openid/callback`) answers [exchange].
 */
class AbsOidcServer : Dispatcher() {
    var allowed: Set<String> = setOf("lirelle://oauth")
    var firstStatus: Int? = null
    var location: (state: String) -> String? = ::providerPage
    var setCookies: List<String> = TheSetCookies
    var exchange: MockResponse = MockResponse(
        code = 200,
        headers = headersOf("Content-Type", "application/json"),
        body = loginJson(),
    )

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            path.endsWith("/auth/openid/callback") -> exchange
            path.endsWith("/auth/openid") -> first(request)
            else -> MockResponse(code = 404, body = "Not found")
        }
    }

    private fun first(request: RecordedRequest): MockResponse {
        firstStatus?.let { return MockResponse(code = it, body = "Nope") }
        if (request.url.queryParameter("redirect_uri") !in allowed) {
            return MockResponse(code = 400, body = "Invalid redirect_uri")
        }
        val state = request.url.queryParameter("state").orEmpty()
        val sent = listOfNotNull(location(state)?.let { "Location" to it }) + setCookies.map { "Set-Cookie" to it }
        val headers = sent.flatMap { it.toList() }
        return MockResponse(code = 302, headers = headersOf(*headers.toTypedArray()), body = "Found")
    }
}

/** The provider's page Audiobookshelf sends the browser to, for a sign-in begun with [state], at [origin]. */
fun providerPage(state: String, origin: String = "https://idp.example.com"): String =
    "$origin/application/o/authorize/?client_id=abs&scope=openid&response_type=code" +
        "&redirect_uri=https%3A%2F%2Fabs.example.com%2Fauth%2Fopenid%2Fmobile-redirect" +
        "&state=$state&code_challenge=x&code_challenge_method=S256"

/** The relative redirect Audiobookshelf answers a failed exchange with: to its web sign-in page. */
fun failedExchange(): MockResponse = MockResponse(
    code = 302,
    headers = headersOf("Location", "/login?error=Error%20in%20callback&autoLaunch=0"),
    body = "Found. Redirecting to /login?error=Error%20in%20callback&autoLaunch=0",
)

/** A plain-text answer of [code] to the exchange: `No session` (400), a refusal, the login limit. */
fun exchangeAnswer(code: Int, body: String = "Nope"): MockResponse =
    MockResponse(code = code, headers = headersOf("Content-Type", "text/plain; charset=utf-8"), body = body)

/** The pending sign-in, in memory: the app's is persisted, which the sign-in does not see. */
class MemoryPendingOidc : PendingOidcStore {
    var pending: PendingOidcRequest? = null

    override suspend fun save(request: PendingOidcRequest) {
        pending = request
    }

    override suspend fun takeWhen(meant: (PendingOidcRequest) -> Boolean): PendingOidcRequest? =
        pending?.takeIf(meant)?.also { pending = null }

    override suspend fun peek(): PendingOidcRequest? = pending

    override suspend fun isPending(): Boolean = pending != null

    override suspend fun clear() {
        pending = null
    }
}
