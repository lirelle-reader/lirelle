package app.tfdn.lirelle.feature.login

import android.webkit.WebView
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
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.TestUserAgent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Server = "https://bd.example.com"

/**
 * The provider's page in a WebView of the app: the system's Back, or the bar's close button, closes it and returns
 * to the sign-in; every cookie and the pages' storage go with it — the provider's session too — and what an attempt
 * before left is gone before it opens.
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
