package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.source.bookorbit.network.dto.AppInfoDto
import app.tfdn.lirelle.source.bookorbit.network.dto.AuthUserDto
import app.tfdn.lirelle.source.bookorbit.network.dto.LoginOptionsDto
import app.tfdn.lirelle.source.bookorbit.network.dto.LoginRequest
import app.tfdn.lirelle.source.bookorbit.network.dto.NativeAuthDto
import app.tfdn.lirelle.source.bookorbit.network.dto.OidcCallbackRequest
import app.tfdn.lirelle.source.bookorbit.network.dto.OidcStateDto
import app.tfdn.lirelle.source.bookorbit.network.dto.RefreshDto
import app.tfdn.lirelle.source.bookorbit.network.dto.RefreshRequest
import app.tfdn.lirelle.source.bookorbit.network.dto.ServerIdentityDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Routes that need no access token. Paths are relative to `<server>/api/v1/`. */
internal interface PublicApi {
    /** Returns the whole response: the probe needs the URL that answered after redirects. */
    @GET("server-identity")
    suspend fun serverIdentity(): Response<ServerIdentityDto>

    @GET("auth/login-options")
    suspend fun loginOptions(): LoginOptionsDto

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): NativeAuthDto

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshDto

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest)

    @POST("auth/oidc/{slug}/state")
    suspend fun oidcState(@Path("slug") slug: String): OidcStateDto

    @POST("auth/oidc/callback")
    suspend fun oidcCallback(@Body body: OidcCallbackRequest): NativeAuthDto
}

/** Routes called with the session's access token. */
internal interface AccountApi {
    @GET("auth/me")
    suspend fun me(): AuthUserDto

    @GET("app-info")
    suspend fun appInfo(): AppInfoDto
}
