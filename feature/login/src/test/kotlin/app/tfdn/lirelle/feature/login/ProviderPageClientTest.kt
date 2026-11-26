package app.tfdn.lirelle.feature.login

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Server = "https://bd.example.com"
private const val SignedIn = "$Server/?server_redirect=Y"

/**
 * What watches the provider's pages: the server's page that ends the flow is told once, from the main frame only —
 * by the navigation, by the page starting when no navigation was asked about, or by a move within the page's own
 * document; a page in cleartext outside the server ends it as unencrypted; the page shown is told for the bar; a
 * renderer that dies closes the page.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderPageClientTest {

    /** A WebView that counts the times it was told to load no further. */
    private class Watched(context: Context) : WebView(context) {
        var stops = 0
            private set

        override fun stopLoading() {
            stops++
        }
    }

    private val view = Watched(ApplicationProvider.getApplicationContext())
    private val outcomes = mutableListOf<ProviderPageOutcome>()
    private val pages = mutableListOf<String>()
    private var gone = 0
    private var sessionCookie = false
    private val client = client(ServerKind.Komga)

    private fun client(kind: ServerKind) = ProviderPageClient(
        kind = kind,
        serverUrl = Server,
        hasSessionCookie = { sessionCookie },
        onOutcome = { outcomes += it },
        onGone = { gone++ },
        onPage = { pages += it },
    )

    @After
    fun tearDown() {
        view.destroy()
    }

    @Test
    fun `the main frame reaching the server's page ends the flow there`() {
        assertThat(client.shouldOverrideUrlLoading(view, request(SignedIn))).isTrue()

        assertThat(outcomes).containsExactly(ProviderPageOutcome.SignedIn)
    }

    /** Kavita's pages say nothing in their address: its own page ends the flow once the session's cookie is held. */
    @Test
    fun `a Kavita server's page ends the flow once the session's cookie is held`() {
        val kavita = client(ServerKind.Kavita)

        assertThat(kavita.shouldOverrideUrlLoading(view, request("$Server/oidc/login"))).isFalse()
        assertThat(kavita.shouldOverrideUrlLoading(view, request("$Server/"))).isFalse()
        assertThat(outcomes).isEmpty()

        sessionCookie = true
        assertThat(kavita.shouldOverrideUrlLoading(view, request("$Server/"))).isTrue()
        assertThat(outcomes).containsExactly(ProviderPageOutcome.SignedIn)
    }

    @Test
    fun `a Kavita page that starts with the session's cookie held ends the flow, and loads no further`() {
        val kavita = client(ServerKind.Kavita)
        sessionCookie = true

        kavita.onPageStarted(view, "$Server/", null)

        assertThat(outcomes).containsExactly(ProviderPageOutcome.SignedIn)
        assertThat(view.stops).isEqualTo(1)
    }

    @Test
    fun `Kavita's login page with an error ends the flow as refused`() {
        val kavita = client(ServerKind.Kavita)
        val refused = "$Server/login?skipAutoLogin=true&error=x"

        assertThat(kavita.shouldOverrideUrlLoading(view, request(refused))).isTrue()

        assertThat(outcomes).containsExactly(ProviderPageOutcome.Refused)
    }

    /**
     * Kavita's application moves by itself, within its document, from the page the server sent the WebView to: no
     * navigation is asked about and no page starts. Such a move ends the flow too, once the cookie is held.
     */
    @Test
    fun `a move within Kavita's application ends the flow once the session's cookie is held`() {
        val kavita = client(ServerKind.Kavita)

        kavita.onPageStarted(view, "$Server/", null)
        kavita.doUpdateVisitedHistory(view, "$Server/", false)
        kavita.doUpdateVisitedHistory(view, "$Server/login", false)
        assertThat(outcomes).isEmpty()
        assertThat(view.stops).isEqualTo(0)

        sessionCookie = true
        kavita.doUpdateVisitedHistory(view, "$Server/home", false)

        assertThat(outcomes).containsExactly(ProviderPageOutcome.SignedIn)
        assertThat(view.stops).isEqualTo(1)
    }

    @Test
    fun `a move within Kavita's application to its login page with an error ends the flow as refused`() {
        val kavita = client(ServerKind.Kavita)

        kavita.doUpdateVisitedHistory(view, "$Server/login?skipAutoLogin=true&error=x", false)

        assertThat(outcomes).containsExactly(ProviderPageOutcome.Refused)
    }

    /** Komga's end is the page its server redirects to, `server_redirect=Y`: a page visited without it ends nothing. */
    @Test
    fun `a page visited on a Komga server ends nothing without the server's redirect`() {
        client.doUpdateVisitedHistory(view, "$Server/", false)
        client.doUpdateVisitedHistory(view, "$Server/login", true)
        client.doUpdateVisitedHistory(view, "https://auth.example.com/if/flow/", false)

        assertThat(outcomes).isEmpty()
        assertThat(pages).isEmpty()
        assertThat(view.stops).isEqualTo(0)
    }

    @Test
    fun `the flow ends once, whether a page starts or is visited`() {
        client.onPageStarted(view, SignedIn, null)
        client.doUpdateVisitedHistory(view, SignedIn, false)

        assertThat(outcomes).containsExactly(ProviderPageOutcome.SignedIn)
    }

    @Test
    fun `a frame inside the page ends nothing`() {
        assertThat(client.shouldOverrideUrlLoading(view, request(SignedIn, mainFrame = false))).isFalse()

        assertThat(outcomes).isEmpty()
    }

    @Test
    fun `the provider's own pages go on`() {
        val provider = "https://auth.example.com/if/flow/default-authentication-flow/"

        assertThat(client.shouldOverrideUrlLoading(view, request(provider))).isFalse()

        assertThat(outcomes).isEmpty()
    }

    @Test
    fun `the flow ends once, however often the server's page is reached`() {
        client.shouldOverrideUrlLoading(view, request(SignedIn))
        client.onPageStarted(view, SignedIn, null)
        client.shouldOverrideUrlLoading(view, request("$Server/login?server_redirect=Y&error=x"))

        assertThat(outcomes).containsExactly(ProviderPageOutcome.SignedIn)
    }

    /** A redirect the WebView follows without asking (a first load, some server redirects) only starts the page. */
    @Test
    fun `a page that starts at the server's page ends the flow too`() {
        client.onPageStarted(view, "$Server/login?server_redirect=Y&error=denied", null)

        assertThat(outcomes).containsExactly(ProviderPageOutcome.Refused)
    }

    @Test
    fun `a page in cleartext outside the server ends the flow as unencrypted`() {
        assertThat(client.shouldOverrideUrlLoading(view, request("http://auth.example.com/login"))).isTrue()

        assertThat(outcomes).containsExactly(ProviderPageOutcome.Unencrypted)
    }

    @Test
    fun `each page that starts is told, for the bar`() {
        client.onPageStarted(view, "https://auth.example.com/if/flow/", null)

        assertThat(pages).containsExactly("https://auth.example.com/if/flow/")
    }

    @Test
    fun `a renderer that dies closes the page, once, and is handled`() {
        assertThat(client.onRenderProcessGone(view, null)).isTrue()
        assertThat(client.onRenderProcessGone(view, null)).isTrue()

        assertThat(gone).isEqualTo(1)
        assertThat(outcomes).isEmpty()
    }

    @Test
    fun `a renderer that dies after the flow ended closes nothing more`() {
        client.shouldOverrideUrlLoading(view, request(SignedIn))

        client.onRenderProcessGone(view, null)

        assertThat(gone).isEqualTo(0)
    }

    private fun request(url: String, mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = true
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
