package app.tfdn.lirelle.source.bookorbit.network

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class OidcUrlsTest {

    @Test
    fun `authorization url carries every required parameter`() {
        val url = buildAuthorizationUrl(
            authorizationEndpoint = "https://auth.example.com/application/o/authorize/",
            clientId = "client-1",
            scopes = "openid profile email",
            state = "state-1",
            nonce = "nonce-1",
            codeChallenge = "challenge-1",
        ).toHttpUrl()

        assertThat(url.host).isEqualTo("auth.example.com")
        assertThat(url.encodedPath).isEqualTo("/application/o/authorize/")
        assertThat(url.queryParameter("response_type")).isEqualTo("code")
        assertThat(url.queryParameter("client_id")).isEqualTo("client-1")
        assertThat(url.queryParameter("redirect_uri")).isEqualTo("bookorbit://oauth2-callback")
        assertThat(url.queryParameter("scope")).isEqualTo("openid profile email")
        assertThat(url.queryParameter("code_challenge")).isEqualTo("challenge-1")
        assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(url.queryParameter("state")).isEqualTo("state-1")
        assertThat(url.queryParameter("nonce")).isEqualTo("nonce-1")
    }

    @Test
    fun `authorization url keeps query parameters already on the endpoint`() {
        val url = buildAuthorizationUrl("https://auth.example.com/authorize?tenant=a", "c", "openid", "s", "n", "ch")
            .toHttpUrl()

        assertThat(url.queryParameter("tenant")).isEqualTo("a")
        assertThat(url.queryParameter("client_id")).isEqualTo("c")
    }

    @Test
    fun `redirect with code and state is parsed`() {
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?code=abc%2F1&state=state-1"))
            .isEqualTo(OidcRedirect.Code(code = "abc/1", state = "state-1"))
    }

    @Test
    fun `redirect with an error is refused`() {
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?error=access_denied&state=state-1"))
            .isEqualTo(OidcRedirect.Denied(state = "state-1"))
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?error=access_denied&code=abc&state=state-1"))
            .isEqualTo(OidcRedirect.Denied(state = "state-1"))
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?error=access_denied"))
            .isEqualTo(OidcRedirect.Refused)
    }

    @Test
    fun `redirect without code or state is refused`() {
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?state=state-1"))
            .isEqualTo(OidcRedirect.Denied(state = "state-1"))
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?code=abc")).isEqualTo(OidcRedirect.Refused)
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback")).isEqualTo(OidcRedirect.Refused)
    }

    @Test
    fun `a uri for another target is refused`() {
        assertThat(OidcRedirect.parse("bookorbit://something-else?code=abc&state=s")).isEqualTo(OidcRedirect.Refused)
        assertThat(OidcRedirect.parse("https://evil.example.com/?code=abc&state=s")).isEqualTo(OidcRedirect.Refused)
    }

    @Test
    fun `a redirect that cannot be decoded is refused`() {
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?code=%zz&state=state-1"))
            .isEqualTo(OidcRedirect.Refused)
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?code=abc&state=%")).isEqualTo(OidcRedirect.Refused)
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?%zz=1&code=abc&state=s"))
            .isEqualTo(OidcRedirect.Refused)
    }

    @Test
    fun `a question mark inside the fragment does not start the query`() {
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback#x?code=abc&state=state-1"))
            .isEqualTo(OidcRedirect.Refused)
    }

    @Test
    fun `a fragment after the query is ignored`() {
        assertThat(OidcRedirect.parse("bookorbit://oauth2-callback?code=abc&state=state-1#frag?x=1"))
            .isEqualTo(OidcRedirect.Code(code = "abc", state = "state-1"))
    }
}
