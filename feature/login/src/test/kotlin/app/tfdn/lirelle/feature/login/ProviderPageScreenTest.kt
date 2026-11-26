package app.tfdn.lirelle.feature.login

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.children
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.TestUserAgent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val Server = "https://bd.example.com"

/** The value of a cookie written once the page has ended. */
private const val Late = "written-late"

/**
 * The provider's page in a WebView of the app: the system's Back, or the bar's close button, closes it and returns
 * to the sign-in; every cookie and the pages' storage go with it — the provider's session too — and what an attempt
 * before left is gone before it opens. However the page ends, what is still written while its WebView is there is
 * forgotten once the WebView is destroyed.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderPageScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeWebCookies()
    private val ends = mutableListOf<ProviderPageEnd>()
    private val page = ProviderPage(Server, ServerKind.Komga, "$Server/oauth2/authorization/authentik", "Authentik")

    private fun show() {
        compose.setContent {
            LirelleTheme { ProviderPageScreen(page, ProviderPageCookies(store), TestUserAgent, onEnd = { ends += it }) }
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `Back closes the page, and the server's and the provider's sessions go with it`() {
        show()
        store.add("bd.example.com", "/", "KOMGA-SESSION", "half-way")
        store.add("auth.example.com", "/", "authentik_session", "a1")

        pressBack()

        assertThat(ends).containsExactly(ProviderPageEnd.Closed)
        assertThat(store.isEmpty).isTrue()
    }

    /**
     * Over a profile, the screens' own Back handler leaves the whole sign-in from any step; the page, opened over
     * the step, takes Back first.
     */
    @Test
    fun `over a profile, Back closes the page and stays in the sign-in`() {
        var left = 0
        val screens = SignInOverProfileViewModel(FakeProfiles(Camille), SignInPurpose.Add)
        compose.setContent {
            LirelleTheme {
                SignInOverProfile(SignInPurpose.Add, onLeave = { left++ }, viewModel = screens) { _, _ ->
                    var open by remember { mutableStateOf(false) }
                    if (open) {
                        ProviderPageScreen(
                            page,
                            ProviderPageCookies(store),
                            TestUserAgent,
                            onEnd = { ends += it; open = false },
                        )
                    } else {
                        Text("sign-in-step", Modifier.clickable { open = true })
                    }
                }
            }
        }
        compose.onNodeWithText("sign-in-step").performClick()
        compose.waitForIdle()

        pressBack()

        assertThat(ends).containsExactly(ProviderPageEnd.Closed)
        assertThat(left).isEqualTo(0)
        compose.onNodeWithText("sign-in-step").assertIsDisplayed()
    }

    @Test
    fun `the bar's button closes the page too, and names the provider and the host of the page`() {
        show()
        compose.onNodeWithText("Authentik").assertIsDisplayed()
        compose.onNodeWithText("bd.example.com").assertIsDisplayed()
        store.add("auth.example.com", "/", "authentik_session", "a1")

        compose.onNodeWithContentDescription("Close").performClick()

        assertThat(ends).containsExactly(ProviderPageEnd.Closed)
        assertThat(store.isEmpty).isTrue()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the close button is named in French`() {
        show()

        compose.onNodeWithContentDescription("Fermer").assertIsDisplayed()
    }

    @Test
    fun `what an attempt before left is removed before the page opens`() {
        store.add("bd.example.com", "/", "KOMGA-SESSION", "left-from-before")
        store.add("auth.example.com", "/", "authentik_session", "a1")

        show()

        assertThat(store.isEmpty).isTrue()
        assertThat(store.clearings).isEqualTo(1)
        assertThat(ends).isEmpty()
    }

    /**
     * The page shown until it ends, then gone from the screen. As it ends, [Late] is written: what a response
     * still on its way, or the page's own script, leaves once everything was removed, the WebView being still
     * there.
     */
    private fun showUntilItEnds() {
        compose.setContent {
            LirelleTheme {
                var open by remember { mutableStateOf(true) }
                if (open) {
                    ProviderPageScreen(
                        page,
                        ProviderPageCookies(store),
                        TestUserAgent,
                        onEnd = {
                            ends += it
                            store.add("bd.example.com", "/", "KOMGA-SESSION", Late)
                            open = false
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The WebView the page is shown in. */
    private fun webView(): WebView {
        fun find(view: View): WebView? =
            view as? WebView ?: (view as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
        return checkNotNull(find(compose.activity.window.decorView)) { "No WebView on screen" }
    }

    /** What watches the WebView's pages, told [event] as the WebView would tell it. */
    private fun tellClient(event: WebViewClient.(WebView) -> Unit) {
        val webView = webView()
        compose.runOnUiThread { shadowOf(webView).webViewClient.event(webView) }
        compose.waitForIdle()
    }

    private fun assertEverythingForgottenAfter(end: ProviderPageEnd) {
        assertThat(ends).containsExactly(end)
        assertThat(store.isEmpty).isTrue()
    }

    @Test
    fun `what is written as the page is closed is forgotten once the page has gone`() {
        showUntilItEnds()

        pressBack()

        assertEverythingForgottenAfter(ProviderPageEnd.Closed)
    }

    @Test
    fun `what is written as the page ends signed in is forgotten once the page has gone`() {
        showUntilItEnds()
        store.add("bd.example.com", "/", "KOMGA-SESSION", "s1")

        tellClient { onPageStarted(it, "$Server/?server_redirect=Y", null) }

        assertEverythingForgottenAfter(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1"))
    }

    /** As Kavita's application does, once loaded: the flow ends while its page runs. */
    @Test
    fun `what is written as the page ends on a move within the server's page is forgotten once it has gone`() {
        showUntilItEnds()
        store.add("bd.example.com", "/", "KOMGA-SESSION", "s1")

        tellClient { doUpdateVisitedHistory(it, "$Server/?server_redirect=Y", false) }

        assertEverythingForgottenAfter(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1"))
    }

    @Test
    fun `what is written as the page ends refused is forgotten once the page has gone`() {
        showUntilItEnds()

        tellClient { onPageStarted(it, "$Server/login?server_redirect=Y&error=refused", null) }

        assertEverythingForgottenAfter(ProviderPageEnd.Refused)
    }

    @Test
    fun `what is written as the page ends unencrypted is forgotten once the page has gone`() {
        showUntilItEnds()

        tellClient { onPageStarted(it, "http://auth.example.com/login", null) }

        assertEverythingForgottenAfter(ProviderPageEnd.Unencrypted)
    }

    @Test
    fun `what is written as the renderer dies is forgotten once the page has gone`() {
        showUntilItEnds()

        tellClient { onRenderProcessGone(it, null) }

        assertEverythingForgottenAfter(ProviderPageEnd.Closed)
    }

    /** Nothing can write once the WebView is destroyed: everything is forgotten after that, not before. */
    @Test
    fun `releasing the WebView forgets everything once it is destroyed, what it wrote last included`() {
        val webView = object : WebView(compose.activity) {
            override fun destroy() {
                store.add("bd.example.com", "/", "KOMGA-SESSION", Late)
                super.destroy()
            }
        }

        releaseProviderWebView(webView, ProviderPageCookies(store))

        assertThat(store.isEmpty).isTrue()
        assertThat(store.clearings).isEqualTo(1)
    }

    @Test
    fun `the WebView runs the provider's scripts, reaches no file, and says it is Lirelle's`() {
        val webView = WebView(compose.activity)
        val userAgent = webView.settings.userAgentString

        configureProviderWebView(webView, TestUserAgent)

        with(webView.settings) {
            assertThat(javaScriptEnabled).isTrue()
            assertThat(allowFileAccess).isFalse()
            assertThat(allowContentAccess).isFalse()
            assertThat(userAgentString).isEqualTo("$userAgent Lirelle/test")
        }
        webView.destroy()
    }

    @Test
    fun `the provider still sees the WebView's own agent, Lirelle's added after it`() {
        assertThat(providerUserAgent("Mozilla/5.0 (Linux; Android 16; wv)", TestUserAgent))
            .isEqualTo("Mozilla/5.0 (Linux; Android 16; wv) Lirelle/test")
    }
}
