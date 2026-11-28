package app.tfdn.lirelle.source.audiobookshelf.network.dto

import kotlinx.serialization.Serializable

/**
 * What the server tells anybody (`GET status`), of which the app reads only these.
 *
 * @property app `"audiobookshelf"` on every version since 2.6.0: what tells the server from others.
 * @property serverVersion the version it runs, "2.38.0".
 * @property authMethods how it signs in: `local` (a name and a password), `openid` (its identity provider).
 */
@Serializable
internal data class AbsStatusDto(
    val app: String? = null,
    val serverVersion: String? = null,
    val authMethods: List<String> = emptyList(),
    val authFormData: AbsAuthFormDto? = null,
)

/** What the server's sign-in page shows: the text of its provider's button, empty or absent when not set. */
@Serializable
internal data class AbsAuthFormDto(val authOpenIDButtonText: String? = null)

/** What a password sign-in sends (`POST login`). */
@Serializable
internal data class AbsLoginRequest(val username: String, val password: String) {
    /** Never prints the password: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "AbsLoginRequest(username=$username, password=<redacted>)"
}

/** What a sign-in answers, of which the app reads only the account and its tokens. */
@Serializable
internal data class AbsLoginDto(val user: AbsUserDto)

/**
 * The account, with the tokens of the session a sign-in opened: both in the answer when the sign-in asked for them
 * (`x-return-tokens`), else the refresh token is null. The old token the answer also carries, which never expires,
 * is not read: it is kept nowhere.
 *
 * @property id the account's id, a UUID.
 */
@Serializable
internal data class AbsUserDto(
    val id: String,
    val username: String,
    val type: String? = null,
    val permissions: AbsPermissionsDto? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
) {
    /** Never prints the tokens: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "AbsUserDto(id=$id, username=$username, type=$type)"
}

/** What the account may do, of which the app reads whether it may download. Null: the server did not say. */
@Serializable
internal data class AbsPermissionsDto(val download: Boolean? = null)

/**
 * What a renewal answers (`POST auth/refresh`), of which the app reads only the new pair: the account the answer
 * also carries is read with `api/me`.
 */
@Serializable
internal data class AbsRefreshDto(val user: AbsRenewedDto)

/** The new pair a renewal gives. Either may be missing from an answer the app then judges. */
@Serializable
internal data class AbsRenewedDto(val accessToken: String? = null, val refreshToken: String? = null) {
    /** Never prints the tokens: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "AbsRenewedDto(<redacted>)"
}
