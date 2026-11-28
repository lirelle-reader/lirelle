package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OidcStartTest {

    @Test
    fun `its text names the host and the scheme, never the state of the url`() {
        val start = OidcStart("https://auth.example.com/authorize?state=secret-state&code_challenge=c", "lirelle")

        assertThat(start.toString()).isEqualTo("OidcStart(host=auth.example.com, redirectScheme=lirelle)")
    }

    @Test
    fun `a url that cannot be read is not printed at all`() {
        assertThat(OidcStart("not a url?state=secret-state", null).toString())
            .isEqualTo("OidcStart(host=<redacted>, redirectScheme=null)")
    }
}
