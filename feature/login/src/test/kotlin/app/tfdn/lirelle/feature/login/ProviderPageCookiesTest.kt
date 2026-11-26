package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a provider's page leaves in the WebViews: the server's session, read as the WebView would send it to the
 * server's address, base path included; then everything removed — the provider's own session too, which would
 * otherwise sign the next account added in as the same one, and stay on the device. No other WebView of the app
 * keeps anything there: the reader answers its own requests, and keeps no storage.
 */
class ProviderPageCookiesTest {

    private val store = FakeWebCookies()
    private val cookies = ProviderPageCookies(store)

    @Test
    fun `the server's cookies are read as sent to its address, then everything is removed`() {
        store.add("bd.example.com", "/", "KOMGA-SESSION", "s1")
        store.add("bd.example.com", "/", "theme", "dark")
        store.add("auth.example.com", "/", "authentik_session", "a1")

        assertThat(cookies.take("https://bd.example.com")).isEqualTo("KOMGA-SESSION=s1; theme=dark")

        assertThat(store.isEmpty).isTrue()
        assertThat(store.clearings).isEqualTo(1)
    }

    @Test
    fun `under a base path, the cookies of that path are read first`() {
        store.add("bd.example.com", "/", "KOMGA-SESSION", "at-root")
        store.add("bd.example.com", "/komga/", "KOMGA-SESSION", "s2")
        store.add("bd.example.com", "/other/", "KOMGA-SESSION", "elsewhere")

        assertThat(cookies.take("https://bd.example.com/komga")).isEqualTo("KOMGA-SESSION=s2; KOMGA-SESSION=at-root")

        assertThat(store.isEmpty).isTrue()
    }

    /** The provider signed the account in even when the server set nothing: its session must not stay. */
    @Test
    fun `with no cookie for the server, nothing is read, and the provider's session goes all the same`() {
        store.add("auth.example.com", "/", "authentik_session", "a1")

        assertThat(cookies.take("https://bd.example.com")).isNull()

        assertThat(store.isEmpty).isTrue()
        assertThat(store.clearings).isEqualTo(1)
    }

    @Test
    fun `a page that ends signed in hands the server's cookies over, then everything goes`() {
        store.add("bd.example.com", "/", "KOMGA-SESSION", "s1")
        store.add("auth.example.com", "/", "authentik_session", "a1")

        val end = cookies.endOn(ProviderPageOutcome.SignedIn, "https://bd.example.com")

        assertThat(end).isEqualTo(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1"))
        assertThat(store.isEmpty).isTrue()
    }

    @Test
    fun `a page refused, or ended on a page in cleartext, says which, and everything goes`() {
        store.add("bd.example.com", "/", "KOMGA-SESSION", "s1")
        store.add("auth.example.com", "/", "authentik_session", "a1")

        assertThat(cookies.endOn(ProviderPageOutcome.Refused, "https://bd.example.com"))
            .isEqualTo(ProviderPageEnd.Refused)
        assertThat(store.isEmpty).isTrue()

        store.add("auth.example.com", "/", "authentik_session", "a2")
        assertThat(cookies.endOn(ProviderPageOutcome.Unencrypted, "https://bd.example.com"))
            .isEqualTo(ProviderPageEnd.Unencrypted)
        assertThat(store.isEmpty).isTrue()
        assertThat(store.clearings).isEqualTo(2)
    }

    @Test
    fun `a session's cookie is told by its name, whatever its value and whatever is beside it`() {
        assertThat(hasSessionCookie(".AspNetCore.Cookies=chunks-2", ServerKind.Kavita)).isTrue()
        assertThat(hasSessionCookie("theme=dark;.AspNetCore.Cookies=a==", ServerKind.Kavita)).isTrue()
        assertThat(hasSessionCookie("KOMGA-SESSION=s1", ServerKind.Komga)).isTrue()
        assertThat(hasSessionCookie("theme=dark; KOMGA-SESSION=s1", ServerKind.Komga)).isTrue()
    }

    /** ASP.NET splits a long cookie into parts named after it: `C1`, `C2`… */
    @Test
    fun `the parts of a Kavita session's cookie are the session's`() {
        assertThat(hasSessionCookie(".AspNetCore.CookiesC1=a", ServerKind.Kavita)).isTrue()
        assertThat(hasSessionCookie("theme=dark; .AspNetCore.CookiesC1=a; .AspNetCore.CookiesC2=b", ServerKind.Kavita))
            .isTrue()
        assertThat(hasSessionCookie(".AspNetCore.CookiesC12=l", ServerKind.Kavita)).isTrue()
    }

    @Test
    fun `a name that only begins as the session's is not the session's`() {
        assertThat(hasSessionCookie(".AspNetCore.CookiesFoo=a", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie(".AspNetCore.CookiesC=a", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie(".AspNetCore.CookiesC1x=a", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie(".AspNetCore.Cookies.x=a", ServerKind.Kavita)).isFalse()
    }

    @Test
    fun `the cookies of a sign-in in progress, or another's value, are not the session's`() {
        assertThat(hasSessionCookie(".AspNetCore.Correlation.x=N", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie(".AspNetCore.OpenIdConnect.Nonce.x=N", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie("theme=.AspNetCore.Cookies", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie("x.AspNetCore.Cookies=a", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie("", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie(null, ServerKind.Kavita)).isFalse()
    }

    @Test
    fun `an entry without a value is no cookie`() {
        assertThat(hasSessionCookie(".AspNetCore.Cookies", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie("theme=dark; .AspNetCore.CookiesC1", ServerKind.Kavita)).isFalse()
        assertThat(hasSessionCookie("KOMGA-SESSION", ServerKind.Komga)).isFalse()
    }

    @Test
    fun `a kind whose session is no cookie has none`() {
        assertThat(hasSessionCookie(".AspNetCore.Cookies=a; KOMGA-SESSION=s1", ServerKind.BookOrbit)).isFalse()
    }

    @Test
    fun `the session's cookie is looked for among those sent to the server, by its kind's name, and stays`() {
        store.add("livres.example.com", "/", ".AspNetCore.Correlation.abc", "N")
        store.add("auth.example.com", "/", ".AspNetCore.Cookies", "another-server")
        assertThat(cookies.hasSessionCookie(ServerKind.Kavita, "https://livres.example.com")).isFalse()

        store.add("livres.example.com", "/", ".AspNetCore.CookiesC1", "c1")
        assertThat(cookies.hasSessionCookie(ServerKind.Kavita, "https://livres.example.com")).isTrue()
        assertThat(cookies.hasSessionCookie(ServerKind.Komga, "https://livres.example.com")).isFalse()
        assertThat(cookies.hasSessionCookie(ServerKind.BookOrbit, "https://livres.example.com")).isFalse()

        store.add("bd.example.com", "/komga/", "KOMGA-SESSION", "s1")
        assertThat(cookies.hasSessionCookie(ServerKind.Komga, "https://bd.example.com/komga")).isTrue()
        assertThat(cookies.hasSessionCookie(ServerKind.Komga, "https://bd.example.com")).isFalse()

        assertThat(store.clearings).isEqualTo(0)
    }

    @Test
    fun `forgetting removes every cookie and the pages' storage`() {
        store.add("bd.example.com", "/", "KOMGA-SESSION", "s1")
        store.add("auth.example.com", "/", "authentik_session", "a1")

        cookies.forgetAll()

        assertThat(store.cookies("https://bd.example.com/")).isNull()
        assertThat(store.cookies("https://auth.example.com/")).isNull()
        assertThat(store.clearings).isEqualTo(1)
    }
}
