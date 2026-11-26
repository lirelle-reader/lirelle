package app.tfdn.lirelle.source.kavita.network.dto

import kotlinx.serialization.Serializable

/**
 * What the server says of its identity provider to anybody (`GET api/Settings/oidc`), answered even when none is
 * set up: whether there is one, its name on the sign-in page, and whether passwords are still taken.
 */
@Serializable
internal data class OidcSettingsDto(
    val enabled: Boolean,
    val disablePasswordAuthentication: Boolean,
    val providerName: String,
)

/** What a password sign-in sends (`POST api/Account/login`): the key left empty, as Kavita's own page does. */
@Serializable
internal data class LoginRequest(val username: String, val password: String, val apiKey: String) {
    /** Never prints the password: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "LoginRequest(username=$username, password=<redacted>)"
}

/**
 * The account, as a sign-in and `GET api/Account` answer it, of which the app reads only these. The tokens are
 * those of the session a password opened: null when the account was read with a key or a provider's session.
 *
 * @property kavitaVersion the version the server runs, "0.9.1.4"; null when it tells none, or a null.
 */
@Serializable
internal data class KavitaUserDto(
    val id: Int,
    val username: String,
    val email: String? = null,
    val kavitaVersion: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
) {
    /** Never prints the tokens: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "KavitaUserDto(id=$id, username=$username, kavitaVersion=$kavitaVersion)"
}

/**
 * What a key is made with (`POST api/Account/create-auth-key`): its length, its name, unique among the account's
 * keys, and when it expires — never, for the device's.
 */
@Serializable
internal data class AuthKeyRequest(val keyLength: Int, val name: String, val expiresUtc: String?)

/** A key of the account: its id, by which it is deleted, and the key itself, which Kavita lists in clear. */
@Serializable
internal data class AuthKeyDto(val id: Int, val key: String) {
    /** Never prints the key: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "AuthKeyDto(id=$id, key=<redacted>)"
}
