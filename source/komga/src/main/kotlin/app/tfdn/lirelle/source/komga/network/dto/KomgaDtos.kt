package app.tfdn.lirelle.source.komga.network.dto

import kotlinx.serialization.Serializable

/** An identity provider the server offers (`OAuth2ClientDto`): its name, and the id its flow is begun with. */
@Serializable
internal data class OAuth2ProviderDto(val name: String, val registrationId: String)

/**
 * The account (`UserDto` of `GET /api/v2/users/me`), of which the app reads only these: an account is named by
 * its email, and its rights are its roles (`ADMIN`, `FILE_DOWNLOAD`, `PAGE_STREAMING`…, `USER` always).
 */
@Serializable
internal data class UserDto(val id: String, val email: String, val roles: List<String> = emptyList())

/** What a key is made with (`ApiKeyRequestDto`): its comment, unique among the account's keys. */
@Serializable
internal data class ApiKeyRequest(val comment: String)

/** A key just made (`ApiKeyDto`): its id, by which it is revoked, and the key itself, given this once only. */
@Serializable
internal data class ApiKeyDto(val id: String, val key: String) {
    /** Never prints the key: this text ends up in logs and in failed assertions. */
    override fun toString(): String = "ApiKeyDto(id=$id, key=<redacted>)"
}
