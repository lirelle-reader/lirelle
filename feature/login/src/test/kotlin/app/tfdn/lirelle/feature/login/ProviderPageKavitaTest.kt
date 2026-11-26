package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val Server = "https://livres.example.com"
private const val UnderBasePath = "https://h/kavita/"

/**
 * Where a provider's page ends on Kavita, whose pages are an application that says nothing in its address: the
 * sign-in is done once one of the server's own pages is reached with its session's cookie held, and refused on
 * its login page with an error. The addresses the provider's flow goes through on the server end nothing, nor
 * does a page outside it. BookOrbit never opens such a page.
 */
class ProviderPageKavitaTest {

    private fun kavita(url: String, cookie: Boolean, serverUrl: String = Server): ProviderPageOutcome? =
        providerPageOutcome(ServerKind.Kavita, serverUrl, url) { cookie }

    @Test
    fun `a page of the server with the session's cookie is the sign-in done`() {
        assertThat(kavita("$Server/", cookie = true)).isEqualTo(ProviderPageOutcome.SignedIn)
        assertThat(kavita(Server, cookie = true)).isEqualTo(ProviderPageOutcome.SignedIn)
        assertThat(kavita("$Server/home", cookie = true)).isEqualTo(ProviderPageOutcome.SignedIn)
    }

    @Test
    fun `a page of the server without the session's cookie ends nothing`() {
        assertThat(kavita("$Server/", cookie = false)).isNull()
        assertThat(kavita("$Server/home", cookie = false)).isNull()
        assertThat(kavita("$Server/login", cookie = false)).isNull()
        assertThat(kavita("$Server/login?skipAutoLogin=true", cookie = false)).isNull()
    }

    @Test
    fun `the login page with an error is the sign-in refused, even with a cookie`() {
        val refused = "$Server/login?skipAutoLogin=true&error=x"

        assertThat(kavita(refused, cookie = true)).isEqualTo(ProviderPageOutcome.Refused)
        assertThat(kavita(refused, cookie = false)).isEqualTo(ProviderPageOutcome.Refused)
        assertThat(kavita("$Server/Login?error=", cookie = true)).isEqualTo(ProviderPageOutcome.Refused)
    }

    @Test
    fun `an error on another page than the login page refuses nothing`() {
        assertThat(kavita("$Server/home?error=x", cookie = false)).isNull()
        assertThat(kavita("$Server/home?error=x", cookie = true)).isEqualTo(ProviderPageOutcome.SignedIn)
    }

    @Test
    fun `the addresses the provider's flow goes through end nothing, even with a cookie`() {
        listOf(
            "$Server/oidc/login",
            "$Server/oidc/login?returnUrl=%2F",
            "$Server/OIDC/Login",
            "$Server/signin-oidc",
            "$Server/signin-oidc?code=c&state=s",
            "$Server/Signin-OIDC/",
        ).forEach { assertThat(kavita(it, cookie = true)).isNull() }
    }

    @Test
    fun `under a base path, the server's pages are the base path's`() {
        fun under(url: String, cookie: Boolean = true) = kavita(url, cookie, serverUrl = UnderBasePath)

        assertThat(under("https://h/kavita/")).isEqualTo(ProviderPageOutcome.SignedIn)
        assertThat(under("https://h/kavita")).isEqualTo(ProviderPageOutcome.SignedIn)
        assertThat(under("https://h/kavita/home")).isEqualTo(ProviderPageOutcome.SignedIn)
        assertThat(under("https://h/kavita/", cookie = false)).isNull()
        assertThat(under("https://h/kavita/login?error=x")).isEqualTo(ProviderPageOutcome.Refused)
        assertThat(under("https://h/kavita/oidc/login")).isNull()
        assertThat(under("https://h/kavita/signin-oidc?code=c")).isNull()
        // The host's other pages are not the server's.
        assertThat(under("https://h/")).isNull()
        assertThat(under("https://h/kavitaX/")).isNull()
        assertThat(under("https://h/login?error=x")).isNull()
    }

    @Test
    fun `a page outside the server ends nothing when secure, and the sign-in as unencrypted when in cleartext`() {
        assertThat(kavita("https://auth.example.com/application/o/authorize/?state=s", cookie = true)).isNull()
        assertThat(kavita("https://livres.example.com:8443/", cookie = true)).isNull()
        assertThat(kavita("about:blank", cookie = true)).isNull()
        assertThat(kavita("http://auth.example.com/application/o/authorize/?state=s", cookie = true))
            .isEqualTo(ProviderPageOutcome.Unencrypted)
        assertThat(kavita("http://livres.example.com/", cookie = true)).isEqualTo(ProviderPageOutcome.Unencrypted)
    }

    @Test
    fun `no page of a BookOrbit server ends a provider's page`() {
        fun bookOrbit(url: String) = providerPageOutcome(ServerKind.BookOrbit, Server, url) { true }

        assertThat(bookOrbit("$Server/")).isNull()
        assertThat(bookOrbit("$Server/?server_redirect=Y")).isNull()
        assertThat(bookOrbit("$Server/login?server_redirect=Y&error=x")).isNull()
        assertThat(bookOrbit("https://auth.example.com/authorize")).isNull()
    }
}
