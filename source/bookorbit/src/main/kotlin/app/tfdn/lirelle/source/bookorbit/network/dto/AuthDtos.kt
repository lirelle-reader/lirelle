package app.tfdn.lirelle.source.bookorbit.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Written from the upstream TypeScript types (packages/types/src/auth.ts and app-info.ts).
// Only the fields Lirelle reads are declared.

internal const val NativeClientKind = "native"

/** The server's permission for downloading whole files. */
private const val DownloadPermission = "library_download"

/** Stands for a password or a token in `toString()`: that text ends up in logs. */
private const val Redacted = "<redacted>"

@Serializable
internal data class ServerIdentityDto(
    val product: String = "",
    val protocolVersion: Int = 0,
    val instanceId: String? = null,
)

@Serializable
internal data class LoginOptionsDto(
    val passwordLoginEnabled: Boolean,
    val oidcProviders: List<OidcProviderDto> = emptyList(),
)

@Serializable
internal data class OidcProviderDto(
    val slug: String,
    val displayName: String,
    val enabled: Boolean = true,
    val iconUrl: String? = null,
    val clientId: String,
    val scopes: String,
)

@Serializable
internal data class LoginRequest(
    val username: String,
    val password: String,
    val clientKind: String = NativeClientKind,
    val deviceLabel: String,
) {
    override fun toString(): String =
        "LoginRequest(username=$username, password=$Redacted, clientKind=$clientKind, deviceLabel=$deviceLabel)"
}

@Serializable
internal data class AuthUserDto(
    val id: Long,
    val username: String,
    val name: String,
    val email: String? = null,
    val isDefaultPassword: Boolean = false,
    val isSuperuser: Boolean = false,
    val permissions: List<String> = emptyList(),
    /** Where the server keeps the account's picture, a path on that server; null without one. */
    val avatarUrl: String? = null,
) {
    /** A superuser may do everything, whatever the list says. */
    val canDownload: Boolean get() = isSuperuser || DownloadPermission in permissions
}

@Serializable
internal data class NativeAuthDto(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val sessionId: Long,
    val user: AuthUserDto,
) {
    override fun toString(): String =
        "NativeAuthDto(accessToken=$Redacted, accessTokenExpiresAt=$accessTokenExpiresAt, refreshToken=$Redacted, " +
            "refreshTokenExpiresAt=$refreshTokenExpiresAt, sessionId=$sessionId, user=$user)"
}

/** Body of both `auth/refresh` and `auth/logout`. */
@Serializable
internal data class RefreshRequest(val refreshToken: String) {
    override fun toString(): String = "RefreshRequest(refreshToken=$Redacted)"
}

@Serializable
internal data class RefreshDto(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val sessionId: Long,
    val refreshToken: String? = null,
    val refreshTokenExpiresAt: String? = null,
) {
    override fun toString(): String =
        "RefreshDto(accessToken=$Redacted, accessTokenExpiresAt=$accessTokenExpiresAt, sessionId=$sessionId, " +
            "refreshToken=${refreshToken?.let { Redacted }}, refreshTokenExpiresAt=$refreshTokenExpiresAt)"
}

@Serializable
internal data class OidcStateDto(val state: String, val authorizationEndpoint: String)

@Serializable
internal data class OidcCallbackRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val nonce: String,
    val state: String,
    val clientKind: String = NativeClientKind,
    val deviceLabel: String,
) {
    override fun toString(): String =
        "OidcCallbackRequest(code=$Redacted, codeVerifier=$Redacted, redirectUri=$redirectUri, nonce=$Redacted, " +
            "state=$Redacted, clientKind=$clientKind, deviceLabel=$deviceLabel)"
}

@Serializable
internal data class AppInfoDto(val version: String)

/** Error body produced by the server's exception filter. `message` is a string or an array. */
@Serializable
internal data class ApiErrorBody(
    val statusCode: Int? = null,
    val message: JsonElement? = null,
    val errorCode: String? = null,
    val retryAfterSeconds: Long? = null,
    val errorMeta: JsonObject? = null,
)
