package app.tfdn.lirelle.source.komga.testing

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

/** What Komga answers to a request that carries no credential: a Basic challenge. */
fun MockWebServer.enqueueChallenge() = enqueueJson(
    """{"timestamp":"2026-11-22T10:00:00Z","status":401,"error":"Unauthorized","path":"/api/v2/users/me"}""",
    code = 401,
    "WWW-Authenticate" to "Basic realm=\"Realm\"",
)

fun MockWebServer.enqueueNoContent() = enqueue(MockResponse(code = 204))

fun RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

/** The path as sent, for example `/api/v2/users/me`. */
fun RecordedRequest.pathText(): String = url.encodedPath

/** The roles of [AnnJson]: those a download needs, and `USER`, which Komga lists for every account. */
const val AnnRoles = """"roles":["USER","FILE_DOWNLOAD","PAGE_STREAMING"]"""

/** What Komga answers about the account, from its OpenAPI (`UserDto`), with a field it may add later. */
const val AnnJson = """{"id":"0B5Y2S1ZK4N7Q","email":"ann@example.com",$AnnRoles,""" +
    """"sharedAllLibraries":true,"sharedLibrariesIds":[],"labelsAllow":[],"labelsExclude":[],"later":1}"""

/** [AnnJson] with the roles [roles] instead of its own. */
fun annWithRoles(vararg roles: String): String =
    AnnJson.replace(AnnRoles, """"roles":[${roles.joinToString(",") { "\"$it\"" }}]""")

/** What Komga answers once to the key it made (`ApiKeyDto`): the only time the key itself is given. */
const val KeyJson = """{"id":"0K3Y1D9X2","userId":"0B5Y2S1ZK4N7Q","key":"4f1c0b6e9d8a4c2f8e7d6c5b4a392817",""" +
    """"comment":"Lirelle","createdDate":"2026-11-22T10:15:30Z","lastModifiedDate":"2026-11-22T10:15:30Z"}"""

/** The key of [KeyJson]. */
const val TheKey = "4f1c0b6e9d8a4c2f8e7d6c5b4a392817"

/** The id of the key of [KeyJson]. */
const val TheKeyId = "0K3Y1D9X2"

/** Komga's answer to a key whose comment the account already gave another key. */
const val CollisionJson = """{"timestamp":"2026-11-22T10:15:30Z","status":400,"error":"Bad Request",""" +
    """"message":"ERR_1034","path":"/api/v2/users/me/api-keys"}"""
