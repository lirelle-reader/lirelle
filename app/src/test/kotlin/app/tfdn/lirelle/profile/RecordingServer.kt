package app.tfdn.lirelle.profile

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl

private const val Unavailable = 503
private const val Unauthorized = 401
private const val RenewalPath = "/api/v1/auth/refresh"
private const val FarAway = "2099-01-01T00:00:00.000Z"

/** The access token a server that [RecordingServer.signsIn] gives the account [userId], as its requests carry it. */
internal fun bearerFromServer(userId: Long) = "Bearer access-from-server-$userId"

/**
 * A server that listens, and notes who asks: for each request, its path and the token it carried. It answers
 * that it is unavailable, to everything: never that a book is gone, nor — unless it [refusesRenewals] — that
 * the session is refused.
 *
 * @param refusesRenewals it answers 401 to `auth/refresh`, as a server that no longer takes the session.
 * @param signsIn it can be signed in to with a password, as the sign-in screens do it: it says it is a
 *   BookOrbit server, offers the password, and opens a session for whoever asks — the account `user<N>` is
 *   the account N. Still unavailable for everything a profile asks afterwards.
 * @param has what it has for a profile all the same: its answer to a request, or null for one it has nothing for.
 */
internal class RecordingServer(
    private val refusesRenewals: Boolean = false,
    private val signsIn: Boolean = false,
    private val has: (RecordedRequest) -> MockResponse? = { null },
) : Closeable {

    private val server = MockWebServer()
    private val asked = CopyOnWriteArrayList<Pair<String, String?>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                asked += path to request.headers["Authorization"]
                val refused = refusesRenewals && path == RenewalPath
                val signIn = if (signsIn) signInAnswer(path, request.body?.utf8().orEmpty()) else null
                return signIn ?: has(request) ?: MockResponse(code = if (refused) Unauthorized else Unavailable)
            }
        }
        server.start()
    }

    /** As the app stores a server's address: no trailing slash. */
    val address: String get() = server.url("/").toString().trimEnd('/')

    fun url(path: String): HttpUrl = server.url(path)

    /** How many requests were heard so far, whatever they asked and whoever asked. */
    val heard: Int get() = asked.size

    /** What each request for [path] carried as its token; null for one that carried none. */
    fun authorizationsFor(path: String): List<String?> = asked.filter { it.first == path }.map { it.second }

    /** What each request for a path ending with [end] carried as its token. */
    fun authorizationsForPathsEnding(end: String): List<String?> =
        asked.filter { it.first.endsWith(end) }.map { it.second }

    /** How many times a session was asked to be renewed. */
    val renewals: Int get() = asked.count { it.first == RenewalPath }

    /** Every token this server was ever shown, whoever asked: the profiles' own requests among them. */
    fun tokensShown(): Set<String> = asked.mapNotNull { it.second }.toSet()

    override fun close() = server.close()
}

/** What the sign-in screens ask a server before and at a password sign-in; null for anything else. */
private fun signInAnswer(path: String, body: String): MockResponse? = when (path) {
    "/api/v1/server-identity" -> json("""{"product":"bookorbit","protocolVersion":1,"instanceId":"i"}""")
    "/api/v1/auth/login-options" ->
        json("""{"passwordLoginEnabled":true,"allowRegistration":false,"oidcProviders":[]}""")
    "/api/v1/auth/login" -> json(sessionOf(body))
    else -> null
}

private fun json(body: String) =
    MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)

/** The session the server opens for the account named in [login]: `user7` is the account 7. */
private fun sessionOf(login: String): String {
    val userId = checkNotNull(Regex(""""username":"user(\d+)"""").find(login)).groupValues[1].toLong()
    val user = """{"id":$userId,"username":"user$userId","name":"User $userId","email":null,"active":true,""" +
        """"isSuperuser":false,"isDefaultPassword":false,"settings":{"timezone":"Europe/Paris"},""" +
        """"avatarUrl":null,"provisioningMethod":"local","authenticationMethod":"password","permissions":[]}"""
    return """{"accessToken":"access-from-server-$userId","accessTokenExpiresAt":"$FarAway",""" +
        """"refreshToken":"refresh-from-server-$userId","refreshTokenExpiresAt":"$FarAway",""" +
        """"sessionId":${userId + 100},"user":$user}"""
}
