package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.isOnServer
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.profile.ProfileSession
import java.io.IOException
import okhttp3.HttpUrl

/**
 * Komga's authorizer for the authenticated client of `core/network`: the profile's key in `X-API-Key`, to the
 * profile's own server only. A key is never renewed and never expires: one the server refuses was revoked (in
 * Komga's web interface, most likely). The profile is then kept, marked as needing a sign-in
 * ([ProfileSession.expire]), and until its account signs in again every request for its server fails here, at once
 * and without a word to the server, as it would without a network — the request that met the refusal too.
 *
 * One per profile, over [session], that profile's own.
 */
internal class KomgaKeyAuthorizer(private val session: ProfileSession) : SessionAuthorizer {

    override suspend fun header(target: HttpUrl): AuthHeader? {
        // Read before the session: the mark falls only once the session carries the key of the new sign-in.
        val signInNeeded = session.signInNeeded.value
        val known = session.session.value
        if (!target.isOnServer(known.serverUrl)) return null
        if (signInNeeded) throw IOException("The key was refused: nothing is asked in the profile's name")
        return known.key().header()
    }

    /**
     * The key refused is the one held: the profile is marked, and the request fails. One that is no longer held —
     * the account signed in again while the request was on its way — says nothing of the new key, with which the
     * request is replayed.
     */
    override suspend fun rejected(sent: String, target: HttpUrl): AuthHeader? {
        val known = session.session.value
        if (!target.isOnServer(known.serverUrl)) return null
        val held = known.key()
        if (held.key == sent) session.expire(refused = held)
        // The mark first, as in [header]: it is never down beside the key the server refused.
        val signInNeeded = session.signInNeeded.value
        val now = session.session.value.key()
        if (signInNeeded || now.key == sent) {
            throw IOException("The server refused the key: nothing is asked in the profile's name")
        }
        return now.header()
    }

    private fun Session.key(): Credential.ApiKey =
        credential as? Credential.ApiKey ?: throw IOException("The session holds no key: nothing is asked in its name")

    private fun Credential.ApiKey.header() = AuthHeader(ApiKeyHeader, key)
}
