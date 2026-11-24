package app.tfdn.lirelle.source.bookorbit.network.dto

import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The models must read what the real server sends, not only the hand-written examples. */
class RecordedFixturesTest {

    @Test
    fun `recorded server identity`() {
        val dto = ApiJson.decodeFromString<ServerIdentityDto>(Fixtures.read("recorded/server-identity.json"))

        assertThat(dto.product).isEqualTo("bookorbit")
    }

    @Test
    fun `recorded login options`() {
        val dto = ApiJson.decodeFromString<LoginOptionsDto>(Fixtures.read("recorded/login-options.json"))

        dto.oidcProviders.forEach {
            assertThat(it.slug).isNotEmpty()
            assertThat(it.displayName).isNotEmpty()
            assertThat(it.scopes).isNotEmpty()
        }
    }

    @Test
    fun `recorded profile`() {
        val dto = ApiJson.decodeFromString<AuthUserDto>(Fixtures.read("recorded/auth-me.json"))

        assertThat(dto.username).isNotEmpty()
        assertThat(dto.name).isNotEmpty()
        // Recorded with an account that has no permission and is not a superuser.
        assertThat(dto.canDownload).isFalse()
    }

    @Test
    fun `recorded app info`() {
        val dto = ApiJson.decodeFromString<AppInfoDto>(Fixtures.read("recorded/app-info.json"))

        assertThat(dto.version).isNotEmpty()
    }
}
