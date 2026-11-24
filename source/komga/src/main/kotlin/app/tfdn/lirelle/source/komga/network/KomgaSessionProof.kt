package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.source.komga.network.dto.ApiKeyDto
import app.tfdn.lirelle.source.komga.network.dto.ApiKeyRequest
import app.tfdn.lirelle.source.komga.network.dto.UserDto
import kotlinx.coroutines.CancellationException
import okhttp3.Credentials
import retrofit2.HttpException
import retrofit2.Response

/**
 * One session on a Komga server, used to make the device's key and ended once it is made. Nothing of it is kept.
 *
 * By password ([byPassword]), the account is proven by HTTP Basic, and the session the server opens for it is
 * carried in [SessionHeader] rather than a cookie — asked for empty at first, then the one the server answers — so
 * that every call of the sign-in is of that one session, and the logout ends it. Through a provider ([ofSession]),
 * the session exists already, opened in a WebView: it is carried as the cookie the WebView would send.
 *
 * @param session the session in [SessionHeader]: empty to ask for one, null to send none.
 * @param cookie the session as a `Cookie` header, null for none.
 * @property reading what reading the account is, for what its refusal means: wrong credentials for a password,
 *   a sign-in that did not succeed for a session.
 */
internal class KomgaSessionProof private constructor(
    private val api: KomgaPublicApi,
    private val authorization: String?,
    private var session: String?,
    private val cookie: String?,
    val reading: KomgaCall,
) {

    /** The account signed in. */
    suspend fun account(): UserDto = api.account(authorization, session, cookie).kept()

    /** Makes a key named [comment] for the account: the server gives the key this once. */
    suspend fun mint(comment: String): ApiKeyDto =
        api.createKey(authorization, session, cookie, ApiKeyRequest(comment)).kept()

    /**
     * Ends the session, at best: whatever the server answers — out of reach, the session already gone — the
     * sign-in stands, and the session expires on its own there.
     */
    @Suppress("TooGenericExceptionCaught") // Nothing the logout throws may undo the sign-in.
    suspend fun end() {
        val session = session?.takeIf { it.isNotEmpty() }
        if (session == null && cookie == null) return
        try {
            api.logout(session, cookie).errorBody()?.close()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The session expires on its own on the server.
        }
    }

    /** The body of a successful answer, the session it names kept; the answer as an exception otherwise. */
    private fun <T> Response<T>.kept(): T {
        headers()[SessionHeader]?.takeIf { it.isNotEmpty() }?.let { session = it }
        if (!isSuccessful) throw HttpException(this)
        return body() ?: throw HttpException(this)
    }

    companion object {
        /** A session opened by [email] and [password], which Komga reads as UTF-8 (OkHttp's default is not). */
        fun byPassword(api: KomgaPublicApi, email: String, password: String) = KomgaSessionProof(
            api,
            authorization = Credentials.basic(email, password, Charsets.UTF_8),
            session = "",
            cookie = null,
            reading = KomgaCall.SignIn,
        )

        /** The session [session], the value of Komga's session cookie a provider's sign-in left in a WebView. */
        fun ofSession(api: KomgaPublicApi, session: String) = KomgaSessionProof(
            api,
            authorization = null,
            session = null,
            cookie = "$SessionCookie=$session",
            reading = KomgaCall.Session,
        )
    }
}

/**
 * The session in [cookies] (a `Cookie` header, as a WebView sends it to the server): the first cookie named
 * [SessionCookie] — the WebView lists the cookie of the most precise path first, the server's own under its base
 * path — or null when there is none, or it is empty.
 */
internal fun sessionIn(cookies: String): String? = cookies.split(';')
    .map { it.trim() }
    .firstOrNull { it.substringBefore('=') == SessionCookie }
    ?.substringAfter('=')
    ?.takeIf { it.isNotEmpty() }
