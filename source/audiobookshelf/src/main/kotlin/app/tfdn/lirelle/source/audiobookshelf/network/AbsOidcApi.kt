package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsLoginDto
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * A sign-in through an Audiobookshelf server's identity provider, as the app makes it: the first call, whose
 * redirect is the provider's page, and the exchange of the code the provider gave. Through a client that follows no
 * redirect ([absOidcApi]): the first call's is the browser's to open, and the exchange's is a failure.
 */
internal interface AbsOidcApi {

    /**
     * Begins the sign-in, returning to [returnAddress] with [state]: a 302 to the provider's page, with the cookies
     * of the sign-in's session, when the server allows that address; a 400 otherwise.
     */
    @GET(OidcRoute)
    suspend fun begin(
        @Query("response_type") responseType: String,
        @Query("code_challenge") codeChallenge: String,
        @Query("code_challenge_method") codeChallengeMethod: String,
        @Query("redirect_uri") returnAddress: String,
        @Query("state") state: String,
    ): Response<Unit>

    /**
     * Exchanges [code] for the session, with the [cookies] of the first call: the same answer as a password sign-in
     * on success; a redirect to the server's web sign-in page when the exchange fails; 400 when the sign-in's session
     * is gone.
     */
    @GET(OidcCallbackRoute)
    suspend fun exchange(
        @Query("state") state: String,
        @Query("code") code: String,
        @Query("code_verifier") codeVerifier: String,
        @Header("Cookie") cookies: String?,
    ): Response<AbsLoginDto>
}

/**
 * The client of a sign-in through the provider, made from the sign-in's ([absSignInClient]): it follows no redirect,
 * on either call. The first call's leads to the provider's page, which the browser opens with its own cookies; the
 * exchange's leads to the server's web sign-in page, and means the exchange failed.
 */
internal fun absOidcApi(publicClient: OkHttpClient): AbsApis<AbsOidcApi> = AbsApis(
    absSignInClient(publicClient).newBuilder().followRedirects(false).followSslRedirects(false).build(),
    AbsJson,
    AbsOidcApi::class.java,
)
