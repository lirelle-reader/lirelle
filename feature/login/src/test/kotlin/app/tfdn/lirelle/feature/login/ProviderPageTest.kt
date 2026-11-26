package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val Server = "https://bd.example.com"
private const val UnderBasePath = "https://bd.example.com/komga"

/** Komga's rule, which never asks whether the session's cookie is held. */
private fun komga(serverUrl: String, url: String) =
    providerPageOutcome(ServerKind.Komga, serverUrl, url) { error("Komga asks for no cookie") }

/**
 * Where a provider's page ends on Komga: once the provider is done, the server sends the WebView to its own page,
 * signed in, or to its login page with an error. Both carry `server_redirect=Y`, under the server's base path.
 * Nothing else ends the page: the provider's own pages, the server's callback, a page of another server.
 */
class ProviderPageTest {

    @Test
    fun `the server's own page, asked by the redirect, is the sign-in done`() {
        assertThat(komga(Server, "$Server/?server_redirect=Y")).isEqualTo(ProviderPageOutcome.SignedIn)
        assertThat(komga(Server, "$Server?server_redirect=Y")).isEqualTo(ProviderPageOutcome.SignedIn)
    }

    @Test
    fun `under a base path, the server's own page is the base path's`() {
        assertThat(komga(UnderBasePath, "$UnderBasePath/?server_redirect=Y"))
            .isEqualTo(ProviderPageOutcome.SignedIn)
        assertThat(komga(UnderBasePath, "$UnderBasePath?server_redirect=Y"))
            .isEqualTo(ProviderPageOutcome.SignedIn)
        // The host's root is not the server's page.
        assertThat(komga(UnderBasePath, "$Server/?server_redirect=Y")).isNull()
        assertThat(komga(UnderBasePath, "$Server/komgaX/?server_redirect=Y")).isNull()
    }

    @Test
    fun `the server's login page with an error is the sign-in refused`() {
        assertThat(komga(Server, "$Server/login?server_redirect=Y&error=Access%20denied"))
            .isEqualTo(ProviderPageOutcome.Refused)
        assertThat(komga(UnderBasePath, "$UnderBasePath/login?server_redirect=Y&error=x"))
            .isEqualTo(ProviderPageOutcome.Refused)
        assertThat(komga(UnderBasePath, "$Server/login?server_redirect=Y&error=x")).isNull()
    }

    @Test
    fun `the provider's pages and the server's callback end nothing`() {
        listOf(
            "$Server/oauth2/authorization/authentik",
            "https://auth.example.com/application/o/authorize/?client_id=komga&state=s",
            "https://auth.example.com/if/flow/default-authentication-flow/?next=%2F",
            "$Server/login/oauth2/code/authentik?code=c&state=s",
            "$Server/login",
            "$Server/?server_redirect=N",
            "$Server/",
            "$Server/libraries?server_redirect=Y",
        ).forEach { assertThat(komga(Server, it)).isNull() }
    }

    @Test
    fun `another server's page with the same address ends nothing`() {
        assertThat(komga(Server, "https://auth.example.com/?server_redirect=Y")).isNull()
        assertThat(komga(Server, "https://bd.example.com:8443/?server_redirect=Y")).isNull()
        assertThat(komga(Server, "about:blank")).isNull()
        assertThat(komga(Server, "not an address")).isNull()
    }

    /**
     * A sign-in page in cleartext could be read or changed on its way: only the server's own, whose cleartext the
     * user accepted on the Server screen, may be one. Its own ending, so that the user is told why.
     */
    @Test
    fun `a page in cleartext outside the server ends the sign-in as unencrypted`() {
        assertThat(komga(Server, "http://auth.example.com/application/o/authorize/?state=s"))
            .isEqualTo(ProviderPageOutcome.Unencrypted)
        assertThat(komga(Server, "http://bd.example.com/?server_redirect=Y"))
            .isEqualTo(ProviderPageOutcome.Unencrypted)
        assertThat(komga(Cleartext, "http://auth.lan/if/flow/default-authentication-flow/"))
            .isEqualTo(ProviderPageOutcome.Unencrypted)
        assertThat(komga(Cleartext, "http://komga.lan/oauth2/authorization/authentik"))
            .isEqualTo(ProviderPageOutcome.Unencrypted)
    }

    @Test
    fun `a server in cleartext keeps its own pages, and its provider's secure ones`() {
        assertThat(komga(Cleartext, "$Cleartext/oauth2/authorization/authentik")).isNull()
        assertThat(komga(Cleartext, "$Cleartext/login/oauth2/code/authentik?code=c")).isNull()
        assertThat(komga(Cleartext, "https://auth.example.com/application/o/authorize/")).isNull()
        assertThat(komga(Cleartext, "$Cleartext/?server_redirect=Y"))
            .isEqualTo(ProviderPageOutcome.SignedIn)
    }

    @Test
    fun `the bar names the host of the page shown`() {
        assertThat(pageHost("https://auth.example.com/if/flow/default-authentication-flow/?next=%2F"))
            .isEqualTo("auth.example.com")
        assertThat(pageHost("http://komga.lan:25600/login")).isEqualTo("komga.lan")
        assertThat(pageHost("about:blank")).isNull()
    }
}

private const val Cleartext = "http://komga.lan:25600"
