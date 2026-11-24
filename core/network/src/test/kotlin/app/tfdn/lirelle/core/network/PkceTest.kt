package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PkceTest {

    @Test
    fun `challenge matches the RFC 7636 example`() {
        assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `random tokens are 43 url-safe characters`() {
        assertThat(Pkce.randomToken()).matches("[A-Za-z0-9_-]{43}")
    }

    @Test
    fun `random tokens differ`() {
        assertThat(Pkce.randomToken()).isNotEqualTo(Pkce.randomToken())
    }
}
