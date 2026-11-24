package app.tfdn.lirelle.feature.login

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Server = "https://bd.example.com"
private const val SignedIn = "$Server/?server_redirect=Y"

/**
 * What watches the provider's pages: the server's page that ends the flow is told once, from the main frame only —
 * by the navigation, or by the page starting when no navigation was asked about; a page in cleartext outside the
 * server ends it as unencrypted; the page shown is told for the bar; a renderer that dies closes the page.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderPageClientTest {

    private val view = WebView(ApplicationProvider.getApplicationContext())
    private val outcomes = mutableListOf<ProviderPageOutcome>()
    private val pages = mutableListOf<String>()
    private var gone = 0
    private val client = ProviderPageClient(
        serverUrl = Server,
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
