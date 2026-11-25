package app.tfdn.lirelle.source.bookorbit.network.dto

import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.toProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AuthDtosTest {

    @Test
    fun `server identity`() {
        val dto = ApiJson.decodeFromString<ServerIdentityDto>(Fixtures.read("server-identity.json"))

        assertThat(dto.product).isEqualTo("bookorbit")
        assertThat(dto.protocolVersion).isEqualTo(1)
    }

    @Test
    fun `login options`() {
        val dto = ApiJson.decodeFromString<LoginOptionsDto>(Fixtures.read("login-options.json"))

        assertThat(dto.passwordLoginEnabled).isTrue()
        assertThat(dto.oidcProviders).hasSize(2)
        assertThat(dto.oidcProviders[0])
            .isEqualTo(OidcProviderDto("authentik", "Authentik", true, null, "client-id-1", "openid profile email"))
        assertThat(dto.oidcProviders[1].enabled).isFalse()
    }

    @Test
    fun `native auth response`() {
        val dto = ApiJson.decodeFromString<NativeAuthDto>(Fixtures.read("native-auth.json"))

        assertThat(dto.accessToken).isEqualTo("access-1")
        assertThat(dto.accessTokenExpiresAt).isEqualTo("2026-11-13T08:15:00.000Z")
        assertThat(dto.sessionId).isEqualTo(42)
        assertThat(dto.user)
            .isEqualTo(
                AuthUserDto(1, "reader", "Ann Reader", "ann@example.com", permissions = listOf("library_download")),
            )
        assertThat(dto.user.canDownload).isTrue()
    }

    @Test
    fun `refresh response`() {
        val dto = ApiJson.decodeFromString<RefreshDto>(Fixtures.read("refresh.json"))

        assertThat(dto.accessToken).isEqualTo("access-2")
        assertThat(dto.refreshToken).startsWith("2222")
        assertThat(dto.refreshTokenExpiresAt).isEqualTo("2026-11-20T08:15:00.000Z")
    }

    @Test
    fun `oidc state, me and app info`() {
        assertThat(ApiJson.decodeFromString<OidcStateDto>(Fixtures.read("oidc-state.json")).state).isEqualTo("state-1")
        assertThat(ApiJson.decodeFromString<AuthUserDto>(Fixtures.read("auth-me.json")).username).isEqualTo("reader")
        assertThat(ApiJson.decodeFromString<AppInfoDto>(Fixtures.read("app-info.json")).version).isEqualTo("v3.8.0")
    }

    @Test
    fun `the account's picture is read where the server says it keeps one`() {
        val dto = ApiJson.decodeFromString<AuthUserDto>(Fixtures.read("auth-me-with-picture.json"))

        assertThat(dto.avatarUrl).isEqualTo("/api/v1/users/1/avatar?v=1")
        assertThat(dto.toProfile().avatarUrl).isEqualTo("/api/v1/users/1/avatar?v=1")
    }

    @Test
    fun `an account without a picture, or an answer that says nothing of it, has none`() {
        val without = ApiJson.decodeFromString<AuthUserDto>(Fixtures.read("recorded/auth-me.json"))
        val silent = ApiJson.decodeFromString<AuthUserDto>("""{"id":1,"username":"reader","name":"Ann Reader"}""")

        assertThat(without.toProfile().avatarUrl).isNull()
        assertThat(silent.toProfile().avatarUrl).isNull()
    }

    @Test
    fun `error body with a code`() {
        val dto = ApiJson.decodeFromString<ApiErrorBody>(Fixtures.read("error-account-locked.json"))

        assertThat(dto.errorCode).isEqualTo("account_locked")
        assertThat(dto.retryAfterSeconds).isEqualTo(540)
    }

    @Test
    fun `error body whose message is an array`() {
        val dto = ApiJson.decodeFromString<ApiErrorBody>(Fixtures.read("error-validation.json"))

        assertThat(dto.errorCode).isNull()
        assertThat(dto.retryAfterSeconds).isNull()
    }

    @Test
    fun `login request declares the native client kind`() {
        val json = ApiJson.encodeToString(LoginRequest("reader", "secret", deviceLabel = "Phone (Lirelle)"))
        val fields = ApiJson.decodeFromString<JsonObject>(json)

        assertThat(fields["clientKind"]?.jsonPrimitive?.content).isEqualTo("native")
        assertThat(fields["deviceLabel"]?.jsonPrimitive?.content).isEqualTo("Phone (Lirelle)")
        assertThat(fields.keys).containsExactly("username", "password", "clientKind", "deviceLabel")
    }

    @Test
    fun `oidc callback request declares the native client kind`() {
        val json = ApiJson.encodeToString(
            OidcCallbackRequest(
                "code-1",
                "verifier-1",
                "bookorbit://oauth2-callback",
                "nonce-1",
                "state-1",
                deviceLabel = "Phone (Lirelle)",
            ),
        )
        val fields = ApiJson.decodeFromString<JsonObject>(json)

        assertThat(fields["clientKind"]?.jsonPrimitive?.content).isEqualTo("native")
        assertThat(fields.keys)
            .containsExactly("code", "codeVerifier", "redirectUri", "nonce", "state", "clientKind", "deviceLabel")
    }

    @Test
    fun `requests and answers holding secrets do not print them`() {
        val printed = listOf(
            LoginRequest("reader", "secret-password", deviceLabel = "Phone (Lirelle)"),
            RefreshRequest("secret-refresh"),
            OidcCallbackRequest(
                "secret-code",
                "secret-verifier",
                "bookorbit://oauth2-callback",
                "secret-nonce",
                "secret-state",
                deviceLabel = "Phone (Lirelle)",
            ),
            ApiJson.decodeFromString<NativeAuthDto>(
                Fixtures.read("native-auth.json").replace("access-1", "secret-access"),
            ),
            RefreshDto("secret-access", "2026-11-13T08:15:00Z", sessionId = 42, refreshToken = "secret-refresh"),
        ).map { it.toString() }

        printed.forEach { assertThat(it).doesNotContain("secret") }
        assertThat(printed[3]).doesNotContain("1".repeat(64))
        assertThat(printed[0]).contains("reader")
    }

    @Test
    fun `app info lists the server's features, none when it sends none`() {
        assertThat(ApiJson.decodeFromString<AppInfoDto>(Fixtures.read("app-info.json")).features)
            .containsExactly("server-connection-identity")
        assertThat(ApiJson.decodeFromString<AppInfoDto>("""{"version":"v3.4.0"}""").features).isEmpty()
    }
}
