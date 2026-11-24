package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.source.komga.network.dto.ApiKeyDto
import app.tfdn.lirelle.source.komga.network.dto.ApiKeyRequest
import app.tfdn.lirelle.source.komga.network.dto.UserDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** The header a key goes in, on every route. */
internal const val ApiKeyHeader = "X-API-Key"

/**
 * The header that carries a session instead of a cookie: asked for empty, the server answers the session it opened
 * in it, and a request that carries it back is of that session.
 */
internal const val SessionHeader = "X-Auth-Token"

/** The cookie that carries a session in a browser: what a provider's sign-in leaves in the WebView. */
internal const val SessionCookie = "KOMGA-SESSION"

/** The providers' route, from the server's address: what the probe asks first. */
internal const val ProvidersRoute = "api/v1/oauth2/providers"

/** The account's route, from the server's address. */
internal const val AccountRoute = "api/v2/users/me"

/**
 * What is asked of a Komga server without a profile: what tells it from another server, and signing in — the
 * account read and a key made with the session the sign-in opened, the session ended, a key revoked with itself.
 * Answers are read whole ([Response]): their status and headers say as much as their body.
 */
internal interface KomgaPublicApi {

    /** Public: a JSON array of `{name, registrationId}`, empty when no provider is set up. */
    @GET(ProvidersRoute)
    suspend fun providers(): Response<ResponseBody>

    /** Asked without credentials, by the probe: Komga refuses it with a Basic challenge. */
    @GET(AccountRoute)
    suspend fun anonymousAccount(): Response<ResponseBody>

    /** A header left null is not sent: a session goes in its header or its cookie, never both empty. */
    @GET(AccountRoute)
    suspend fun account(
        @Header("Authorization") authorization: String?,
        @Header(SessionHeader) session: String?,
        @Header("Cookie") cookie: String? = null,
    ): Response<UserDto>

    @POST("api/v2/users/me/api-keys")
    suspend fun createKey(
        @Header("Authorization") authorization: String?,
        @Header(SessionHeader) session: String?,
        @Header("Cookie") cookie: String?,
        @Body request: ApiKeyRequest,
    ): Response<ApiKeyDto>

    /** Ends the session the request carries, in its header or its cookie: 204. */
    @POST("api/logout")
    suspend fun logout(
        @Header(SessionHeader) session: String?,
        @Header("Cookie") cookie: String? = null,
    ): Response<Unit>

    /** Revokes the key [keyId], proven by a key of the account: the key itself. */
    @DELETE("api/v2/users/me/api-keys/{keyId}")
    suspend fun revokeKey(@Header(ApiKeyHeader) key: String, @Path("keyId") keyId: String): Response<Unit>
}

/** What a Komga profile asks of its server about its own account, with its key (the authenticated client's). */
internal interface KomgaAccountApi {
    @GET(AccountRoute)
    suspend fun account(): UserDto
}
