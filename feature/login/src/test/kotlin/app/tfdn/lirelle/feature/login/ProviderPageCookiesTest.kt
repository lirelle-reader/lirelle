package app.tfdn.lirelle.feature.login

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
    fun `forgetting removes every cookie and the pages' storage`() {
        store.add("bd.example.com", "/", "KOMGA-SESSION", "s1")
        store.add("auth.example.com", "/", "authentik_session", "a1")

        cookies.forgetAll()

        assertThat(store.cookies("https://bd.example.com/")).isNull()
        assertThat(store.cookies("https://auth.example.com/")).isNull()
        assertThat(store.clearings).isEqualTo(1)
    }
}
