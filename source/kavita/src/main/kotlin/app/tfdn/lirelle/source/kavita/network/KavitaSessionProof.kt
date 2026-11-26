package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyDto
import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyRequest
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto
import app.tfdn.lirelle.source.kavita.network.dto.LoginRequest
import retrofit2.Response

/** How long the device's key is: what Kavita's own page asks for. */
private const val KeyLength = 32

/**
 * One session on a Kavita server, used to make the device's key. Nothing of it is kept: not the password, not the
 * tokens the server answers it with, not the cookie.
 *
 * By password ([byPassword]), the server opens a session and answers its token, which makes the key. Through a
 * provider ([ofSession]), the session exists already, opened in a WebView: it is carried as the cookies the
 * WebView would send.
 *
 * @param login what opens the session, null when it is open already.
 * @param cookie the session as a `Cookie` header, null for none.
 * @property reading what reading the account is, for what its refusal means: wrong credentials for a password,
 *   a sign-in that did not succeed for a session.
 */
internal class KavitaSessionProof private constructor(
    private val api: KavitaPublicApi,
    private val login: LoginRequest?,
    private val cookie: String?,
    val reading: KavitaCall,
) {

    /** The session's token as an `Authorization` header: a password's only, once the server answered it. */
    private var authorization: String? = null

    /** The account signed in, on a server the app works with; the session's token kept for the key. */
    suspend fun account(): KavitaUserDto {
        if (login == null) return api.account(key = null, cookie = cookie).answer().onRecentEnoughServer()
        val account = api.login(login).answer().onRecentEnoughServer()
        val token = account.token?.takeIf { it.isNotEmpty() } ?: throw ApiErrorException(ApiError.Unexpected)
        authorization = "Bearer $token"
        return account
    }

    /** Asks for a key named [name] for the account, one that never expires: the answer, whatever it is. */
    suspend fun mint(name: String): Response<AuthKeyDto> =
        api.createKey(authorization, cookie, AuthKeyRequest(KeyLength, name, expiresUtc = null))

    companion object {
        /** A session opened by [username] and [password]. */
        fun byPassword(api: KavitaPublicApi, username: String, password: String) =
            KavitaSessionProof(api, LoginRequest(username, password, apiKey = ""), cookie = null, KavitaCall.SignIn)

        /** The session in [cookies], Kavita's own cookies a provider's sign-in left in a WebView. */
        fun ofSession(api: KavitaPublicApi, cookies: String) =
            KavitaSessionProof(api, login = null, cookie = cookies, KavitaCall.Session)
    }
}

/**
 * The names of the session cookie and of its pieces, exactly: ASP.NET numbers the pieces `C1`, `C2`… Its other
 * cookies begin alike (`.AspNetCore.Correlation.…`, `.AspNetCore.OpenIdConnect.Nonce.…`) and are no session.
 */
private val SessionCookieName = Regex(Regex.escape(SessionCookie) + """(C\d+)?""")

/**
 * The session in [cookies] (a `Cookie` header, as a WebView sends it to the server), as a `Cookie` header of its
 * own: Kavita's session cookie and its pieces ([SessionCookie]), nothing of the page — or null when there is none.
 * Of two cookies of one name, the first: the WebView lists the cookie of the most precise path first, the
 * server's own under its base path.
 */
internal fun sessionIn(cookies: String): String? = cookies.split(';')
    .map { it.trim() }
    .filter { SessionCookieName.matches(it.substringBefore('=')) && it.substringAfter('=', "").isNotEmpty() }
    .distinctBy { it.substringBefore('=') }
    .joinToString("; ")
    .takeIf { it.isNotEmpty() }
