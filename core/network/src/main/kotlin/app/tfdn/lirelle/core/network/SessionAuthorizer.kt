package app.tfdn.lirelle.core.network

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A header that proves the profile's account to its server, as its source sends it: `Authorization: Bearer …` for
 * BookOrbit, `X-API-Key` for a key.
 */
data class AuthHeader(val name: String, val value: String) {
    /** Never prints the value: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "AuthHeader($name: <redacted>)"
}

/**
 * How the authenticated client proves one profile's account to its server, as the profile's source says: what it
 * asks before each request ([AuthInterceptor]) and when the server answered 401 to what was sent
 * ([TokenAuthenticator]). Which header, how it is renewed, and what a refusal means are the source's: its
 * server's routes and rules.
 *
 * Called on OkHttp's threads, which block while it works: whatever it throws other than an [IOException] reaches
 * the request as one.
 */
interface SessionAuthorizer {
    /**
     * The header to send to [target] right now, renewed first when what it carries is about to expire.
     *
     * @return null when [target] is not on the profile's server, and only then: the request goes out without it.
     * @throws IOException when no header can be given: a renewal could not be made or failed, the server refused
     * the credential (the profile then waits for a sign-in, and every request until then fails here, as without a
     * network), or the profile is gone.
     */
    suspend fun header(target: HttpUrl): AuthHeader?

    /**
     * The server answered 401 to a request for [target] that carried [sent], the value of a header this gave.
     * Asked once per request: a request replayed and refused again is handed back as it is.
     *
     * @return the header to replay the request with, once — the credential renewed, or that of a sign-in made
     * meanwhile — or null to hand the 401 back as it is ([target] not on the profile's server).
     * @throws IOException when the request is to fail as without a network, the 401 not handed back: the
     * credential cannot be renewed, or the server refused it — the source has then marked the profile as needing
     * a sign-in ([app.tfdn.lirelle.core.session.profile.ProfileSession.expire] with the refused credential).
     */
    suspend fun rejected(sent: String, target: HttpUrl): AuthHeader?

    /**
     * The address to send a request for [target] to: [target] itself, unless the source's server takes the
     * credential in the address on some routes. What this returns is as secret as the credential: it is sent, and
     * neither logged nor kept.
     */
    fun address(target: HttpUrl): HttpUrl = target
}

/** Same scheme, host and port as [serverUrl]: what proves the account must never leave the profile's server. */
fun HttpUrl.isOnServer(serverUrl: String): Boolean = serverUrl.toHttpUrlOrNull()?.let(::isSameServer) ?: false

internal fun HttpUrl.isSameServer(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
