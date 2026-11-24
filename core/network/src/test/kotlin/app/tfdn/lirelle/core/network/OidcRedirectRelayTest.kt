package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

class OidcRedirectRelayTest {

    @Test
    fun `a redirect delivered before anyone listens is kept`() = runBlocking {
        val relay = OidcRedirectRelay()

        relay.deliver("bookorbit://oauth2-callback?code=a&state=s")

        assertThat(relay.redirects.first()).isEqualTo("bookorbit://oauth2-callback?code=a&state=s")
    }

    @Test
    fun `a redirect is handed out once`() = runBlocking {
        val relay = OidcRedirectRelay()
        relay.deliver("bookorbit://oauth2-callback?code=a&state=s")
        relay.redirects.first()

        assertThat(withTimeoutOrNull(100) { relay.redirects.first() }).isNull()
    }

    @Test
    fun `only the latest undelivered redirect is kept`() = runBlocking {
        val relay = OidcRedirectRelay()

        relay.deliver("bookorbit://oauth2-callback?code=old&state=s")
        relay.deliver("bookorbit://oauth2-callback?code=new&state=s")

        assertThat(relay.redirects.first()).contains("code=new")
    }
}
