package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyDto
import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyRequest
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto
import app.tfdn.lirelle.source.kavita.network.dto.LoginRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming

/** The header a key goes in, on every route. */
internal const val ApiKeyHeader = "x-api-key"

/**
 * The cookie that carries a session in a browser: what a provider's sign-in leaves in the WebView. A long one is
 * cut in pieces, each named after it (`.AspNetCore.CookiesC1`, `.AspNetCore.CookiesC2`…).
 */
internal const val SessionCookie = ".AspNetCore.Cookies"

/** The health route, from the server's address: what the probe asks first. */
internal const val HealthRoute = "api/Health"

/** Where a provider's sign-in begins, from the server's address: a page, not a route of the API. */
internal const val ProviderSignInPage = "oidc/login"

/** The account's route, from the server's address. */
private const val AccountRoute = "api/Account"

/**
 * What is asked of a Kavita server without a profile: what tells it from another server, and signing in — the
 * account read, a key made with the session the sign-in opened, the account's keys listed, a key deleted. Answers
 * come with their status ([Response]), which says as much as their body; the two public ones are read by whoever
 * asks, within a bound.
 */
internal interface KavitaPublicApi {

    /**
     * Public: the plain text `Ok`. Not read by Retrofit: whoever asks reads as much as an honest answer is long
     * ([textUpTo]), and closes it.
     */
    @Streaming
    @GET(HealthRoute)
    suspend fun health(): Response<ResponseBody>

    /**
     * Public, answered even when no provider is set up: `{enabled, disablePasswordAuthentication, providerName}`.
     * Not read by Retrofit either: whoever asks reads it within its bound ([textUpTo]), and closes it.
     */
    @Streaming
    @GET("api/Settings/oidc")
    suspend fun oidcSettings(): Response<ResponseBody>

    /**
     * Opens a session with a password: the account, with the session's tokens. The password is in the body: the
     * sign-in's client sends it to no other server ([kavitaSignInClient]).
     */
    @POST("$AccountRoute/login")
    suspend fun login(@Body request: LoginRequest): Response<KavitaUserDto>

    /** The account of a key, or of a provider's session in its cookie. A header left null is not sent. */
    @GET(AccountRoute)
    suspend fun account(@Header(ApiKeyHeader) key: String?, @Header("Cookie") cookie: String?): Response<KavitaUserDto>

    /** Makes a key with a session: a password's, in [authorization], or a provider's, in its [cookie]. */
    @POST("$AccountRoute/create-auth-key")
    suspend fun createKey(
        @Header("Authorization") authorization: String?,
        @Header("Cookie") cookie: String?,
        @Body request: AuthKeyRequest,
    ): Response<AuthKeyDto>

    /** Every key of the account, each in clear. */
    @GET("$AccountRoute/auth-keys")
    suspend fun keys(@Header(ApiKeyHeader) key: String): Response<List<AuthKeyDto>>

    /** Deletes the key [keyId], proven by [key], a key of the account. */
    @DELETE("$AccountRoute/auth-key")
    suspend fun deleteKey(@Header(ApiKeyHeader) key: String, @Query("authKeyId") keyId: String): Response<Unit>
}

/** What a Kavita profile asks of its server about its own account, with its key (the authenticated client's). */
internal interface KavitaAccountApi {
    @GET(AccountRoute)
    suspend fun account(): Response<KavitaUserDto>
}
