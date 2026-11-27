package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.network.SessionAuthorizer
import app.tfdn.lirelle.core.network.isOnServer
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.profile.ProfileSession
import java.io.IOException
import okhttp3.HttpUrl

/** The parameter a key goes in, on the routes that take it in their address: Kavita's images and PDF files. */
internal const val ApiKeyParameter = "apiKey"

/**
 * Kavita's authorizer for the authenticated client of `core/network`: the profile's key in `x-api-key`, to the
 * profile's own server only. A key is never renewed: one the server refuses (401, a Bearer challenge and no body)
 * was deleted in Kavita's web interface, or has expired. The profile is then kept, marked as needing a sign-in
 * ([ProfileSession.expire]), and until its account signs in again every request for its server fails here, at once
 * and without a word to the server, as it would without a network — the request that met the refusal too. A key
 * the user pasted is refused as the device's own.
 *
 * One per profile, over [session], that profile's own.
 */
internal class KavitaKeyAuthorizer(private val session: ProfileSession) : SessionAuthorizer {

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

    /**
     * Kavita's images, its pages and its PDF files are asked with the key in their address: the server refuses the
     * header alone on them (0.8.9.1 answers 400). Every other route, and an address [withKey] gives no key for,
     * keeps its own.
     */
    override fun address(target: HttpUrl): HttpUrl =
        if (carriesKey(target.encodedPath)) withKey(target) ?: target else target

    private fun carriesKey(path: String) =
        path.contains(ImageRoutes) || path.endsWith(PageRoute) || path.endsWith(PdfRoute)

    /**
     * [target] with the profile's key as its [ApiKeyParameter], for the routes that take no header: Kavita's
     * images and PDF files, fetched by what cannot be given one, and the images the authenticated client asks for
     * ([address]), which the server refuses with the header alone. Null for an address that is not on the profile's
     * server, while the profile waits for a sign-in, and for a session that holds no key: the key goes nowhere
     * else, and a refused one is not sent again. An address with the key in it is as secret as the key: it is
     * neither logged nor kept.
     */
    fun withKey(target: HttpUrl): HttpUrl? {
        val signInNeeded = session.signInNeeded.value
        val known = session.session.value
        val key = (known.credential as? Credential.ApiKey)?.key
        if (signInNeeded || key == null || !target.isOnServer(known.serverUrl)) return null
        return target.newBuilder().setQueryParameter(ApiKeyParameter, key).build()
    }

    private fun Session.key(): Credential.ApiKey =
        credential as? Credential.ApiKey ?: throw IOException("The session holds no key: nothing is asked in its name")

    private fun Credential.ApiKey.header() = AuthHeader(ApiKeyHeader, key)

    private companion object {
        /** Where Kavita's image routes are, under the server's address and any base path it has. */
        const val ImageRoutes = "/api/Image/"

        /** Where a page of a chapter is, which takes the key in its address. */
        const val PageRoute = "/api/Reader/image"

        /** Where a chapter's PDF file is, which takes the key in its address. */
        const val PdfRoute = "/api/Reader/pdf"
    }
}
