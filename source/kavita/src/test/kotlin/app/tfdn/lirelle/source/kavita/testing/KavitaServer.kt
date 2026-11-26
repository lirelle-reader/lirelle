package app.tfdn.lirelle.source.kavita.testing

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

/** A plain text answer, as Kavita's health and its refusals of a sign-in are. */
fun MockWebServer.enqueueText(body: String, code: Int = 200) =
    enqueue(MockResponse(code = code, headers = headersOf("Content-Type", "text/plain; charset=utf-8"), body = body))

/** What Kavita answers to a credential it refuses, on any protected route: a Bearer challenge and no body. */
fun MockWebServer.enqueueRefusal() =
    enqueue(MockResponse(code = 401, headers = headersOf("WWW-Authenticate", "Bearer")))

/** What Kavita answers to a deletion: 200, and nothing. */
fun MockWebServer.enqueueDone() = enqueue(MockResponse(code = 200))

/** What Kavita answers about its health. */
fun MockWebServer.enqueueHealthy() = enqueueText("Ok")

fun RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

/** The path as sent, for example `/api/Account`. */
fun RecordedRequest.pathText(): String = url.encodedPath

/** What Kavita says of its identity provider when none is set up. */
const val OidcOffJson = """{"autoLogin":false,"disablePasswordAuthentication":false,""" +
    """"providerName":"OpenID Connect","enabled":false}"""

/** What Kavita says of its identity provider, [name], when one is set up, passwords taken or not. */
fun oidcOnJson(name: String = "Authentik", passwordsDisabled: Boolean = false): String =
    """{"autoLogin":false,"disablePasswordAuthentication":$passwordsDisabled,"providerName":"$name","enabled":true}"""

/** The token of the session a password opens: invented, in the shape of a JWT. */
const val TheToken = "eyJhbGciOiJIUzI1NiJ9.eyJuYW1laWQiOiI3In0.s1gn4tur3"

/** The refresh token of that session. */
const val TheRefreshToken = "r3fr35h-t0k3n"

/**
 * What Kavita answers about Ann's account, on a server running [version]: with the session's tokens when a
 * password opened it ([withTokens]), without when a key or a provider's session read it; and fields the app does
 * not read.
 */
fun annJson(version: String = "0.9.1.4", withTokens: Boolean = false): String {
    val tokens = if (withTokens) """"token":"$TheToken","refreshToken":"$TheRefreshToken"""" else """"token":null"""
    return """{"id":7,"username":"ann","email":"ann@example.com","roles":["Pleb","Download"],""" +
        """"kavitaVersion":"$version",$tokens,"authKeys":[],"later":1}"""
}

/** What Kavita answers to the key it made. */
const val KeyJson = """{"id":42,"key":"k4v1t4d3v1c3k3y0123456789abcdefgh","name":"Lirelle",""" +
    """"expiresAtUtc":null,"provider":0}"""

/** The key of [KeyJson]. */
const val TheKey = "k4v1t4d3v1c3k3y0123456789abcdefgh"

/** The id of the key of [KeyJson], as the profile keeps it. */
const val TheKeyId = "42"

/** Kavita's refusal of a key whose name the account already gave another key: plain text, among a stack trace. */
const val NameTakenText = "System.Exception: The name of an auth key must be unique\n   at API.Controllers.Account()"
