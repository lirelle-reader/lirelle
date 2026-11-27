package app.tfdn.lirelle.source.audiobookshelf.testing

import java.time.Instant
import java.util.Base64
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

/** Server address in the form the app stores it: no trailing slash. */
val MockWebServer.serverUrl: String get() = url("/").toString().trimEnd('/')

fun MockWebServer.enqueueJson(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
    val all = listOf("Content-Type" to "application/json") + headers
    enqueue(MockResponse(code = code, headers = headersOf(*all.flatMap { it.toList() }.toTypedArray()), body = body))
}

/** A plain text answer, as Audiobookshelf's refusals are: `Unauthorized`, `Too many requests`. */
fun MockWebServer.enqueueText(body: String, code: Int = 200) =
    enqueue(MockResponse(code = code, headers = headersOf("Content-Type", "text/plain; charset=utf-8"), body = body))

/** What Audiobookshelf answers to a credential it refuses, on any protected route: a bare 401, no challenge. */
fun MockWebServer.enqueueRefusal() = enqueueText("Unauthorized", code = 401)

/** What Audiobookshelf answers past its login limit (40 per 10 minutes per address). */
fun MockWebServer.enqueueTooMany() = enqueueJson("""{"error":"Too many authentication requests"}""", code = 429)

/** What Audiobookshelf answers to a logout: 200, and nothing worth reading. */
fun MockWebServer.enqueueDone() = enqueueText("OK")

fun RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

/** The path as sent, for example `/status`. */
fun RecordedRequest.pathText(): String = url.encodedPath

/**
 * What Audiobookshelf answers to anybody at `GET status`, running [version], signing in by [methods], its provider's
 * button saying [buttonText] (null: the field left out).
 */
fun statusJson(
    version: String = "2.38.0",
    methods: List<String> = listOf("local", "openid"),
    buttonText: String? = "Authentik",
): String {
    val authMethods = methods.joinToString(",") { "\"$it\"" }
    val button = buttonText?.let { """"authOpenIDButtonText":"$it",""" }.orEmpty()
    return """{"app":"audiobookshelf","serverVersion":"$version","isInit":true,"language":"en-us",""" +
        """"authMethods":[$authMethods],"authFormData":{"authLoginCustomMessage":"",$button""" +
        """"authOpenIDAutoLaunch":false}}"""
}

/** When the tests' tokens are made. */
val SignedInAt: Instant = Instant.parse("2026-11-27T10:00:00Z")

/** When the tests' access token expires: an hour after [SignedInAt], as on 2.38. */
val AccessExpiry: Instant = SignedInAt.plusSeconds(3_600)

/** When the tests' refresh token expires: thirty days after [SignedInAt]. */
val RefreshExpiry: Instant = SignedInAt.plusSeconds(2_592_000)

private fun base64Url(text: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

/**
 * A made-up JWT whose payload is [payload], put together here from its three parts: no token is written whole in
 * the sources, where a scan for secrets would take it for a real one. Its signature is no signature.
 */
fun madeUpJwt(payload: String): String =
    listOf(base64Url("""{"alg":"HS256","typ":"JWT"}"""), base64Url(payload), base64Url("not-signed")).joinToString(".")

/** A made-up token of Ann's of [type], expiring at [expiry]. */
fun annToken(type: String, expiry: Instant): String = madeUpJwt(
    """{"userId":"$AnnId","username":"ann","type":"$type","iat":${SignedInAt.epochSecond},""" +
        """"exp":${expiry.epochSecond}}""",
)

/** Ann's account id on the tests' server: a UUID, as Audiobookshelf names its accounts. */
const val AnnId = "5f0e1a8c-6b3d-4c2e-9a71-2d4b8e6f1c90"

/** The access token a sign-in gives. */
val TheAccessToken: String = annToken("access", AccessExpiry)

/** The refresh token a sign-in gives. */
val TheRefreshToken: String = annToken("refresh", RefreshExpiry)

/** The old token every answer also carries, which never expires: never kept. */
val TheOldToken: String = madeUpJwt("""{"userId":"$AnnId","username":"ann","iat":1700000000}""")

/**
 * What Audiobookshelf answers to a sign-in, `POST login` with `x-return-tokens: true`: Ann's account with her
 * tokens — [accessToken] and [refreshToken], each left out when null — the old token, and much the app does not
 * read. [download] is her download permission, the field left out when null.
 */
fun loginJson(
    accessToken: String? = TheAccessToken,
    refreshToken: String? = TheRefreshToken,
    download: Boolean? = true,
): String {
    val access = accessToken?.let { """"accessToken":"$it",""" }.orEmpty()
    val refresh = refreshToken?.let { """"refreshToken":"$it",""" } ?: """"refreshToken":null,"""
    val permission = download?.let { """"download":$it,""" }.orEmpty()
    return """{"user":{"id":"$AnnId","username":"ann","email":null,"type":"user","isActive":true,""" +
        """"isLocked":false,"permissions":{$permission"update":false,"delete":false,"upload":false,""" +
        """"accessAllLibraries":true},"mediaProgress":[],"bookmarks":[],"token":"$TheOldToken",""" +
        """$access$refresh"hasOpenIDLink":false},"userDefaultLibraryId":"lib-1",""" +
        """"serverSettings":{"version":"2.38.0"},"ereaderDevices":[],"Source":"docker"}"""
}
