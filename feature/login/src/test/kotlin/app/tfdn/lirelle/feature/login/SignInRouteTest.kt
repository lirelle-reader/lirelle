package app.tfdn.lirelle.feature.login

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import androidx.activity.ComponentActivity
import androidx.browser.auth.AuthTabIntent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SignInRouteTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val auth = FakeAuthRepository().apply {
        serverUrl.value = "https://books.example.com"
        loginOptionsResult = ApiResult.Success(
            LoginOptions(
                passwordEnabled = false,
                oidcProviders = listOf(OidcProvider("authentik", "Authentik", "client-id-1", "openid")),
            ),
        )
    }
    private val redirect = "bookorbit://oauth2-callback?code=code-1&state=state-1"

    private fun show(onChangeServer: () -> Unit = {}) {
        val viewModel = SignInViewModel(auth, OidcRedirectRelay(), server = null, kind = null)
        compose.setContent {
            LirelleTheme {
                SignInRoute(onChangeServer = onChangeServer, viewModel = viewModel)
            }
        }
    }

    private fun providerButton() =
        compose.onNodeWithText(application.getString(R.string.sign_in_with_provider, "Authentik"))

    /** Chooses the provider and returns the intent the browser was started with. */
    private fun startSignIn(): Intent {
        providerButton().performClick()
        compose.waitForIdle()
        return shadowOf(compose.activity).nextStartedActivityForResult.intent
    }

    /** Plays the browser finishing with [resultCode] and, for a redirect, [redirectUri]. */
    private fun browserAnswers(request: Intent, resultCode: Int, redirectUri: String? = null) {
        compose.runOnUiThread {
            val data = redirectUri?.let { Intent().setData(it.toUri()) }
            shadowOf(compose.activity).receiveResult(request, resultCode, data)
        }
        compose.waitForIdle()
    }

    /** Installs a default browser that opens Custom Tabs, with Auth Tab when [authTab]. */
    @Suppress("DEPRECATION") // No other way to answer the implicit intents the library resolves.
    private fun installBrowser(authTab: Boolean) {
        val packageManager = shadowOf(application.packageManager)
        val launcher = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = BrowserPackage
                name = "$BrowserPackage.Main"
            }
        }
        packageManager.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, "http://".toUri()), launcher)
        val service = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = BrowserPackage
                name = "$BrowserPackage.Tabs"
            }
            filter = IntentFilter(CustomTabsService).apply {
                if (authTab) addCategory("androidx.browser.auth.category.AuthTab")
            }
        }
        packageManager.addResolveInfoForIntent(Intent(CustomTabsService), service)
        packageManager.addResolveInfoForIntent(Intent(CustomTabsService).setPackage(BrowserPackage), service)
    }

    /** Takes the Custom Tabs service of the default browser away, and Auth Tab with it. */
    @Suppress("DEPRECATION") // The counterpart of the deprecated call that installed it.
    private fun uninstallBrowser() {
        val packageManager = shadowOf(application.packageManager)
        packageManager.removeResolveInfosForIntent(Intent(CustomTabsService), BrowserPackage)
        packageManager.removeResolveInfosForIntent(Intent(CustomTabsService).setPackage(BrowserPackage), BrowserPackage)
    }

    @Test
    fun `a device with no browser shows an error instead of crashing`() {
        // Makes startActivity throw ActivityNotFoundException, as a device with no browser does.
        shadowOf(application).checkActivities(true)
        show()

        providerButton().performClick()

        compose.onNodeWithText(application.getString(R.string.error_browser_unavailable)).assertIsDisplayed()
        assertThat(auth.calls).contains("cancelOidc")
    }

    @Test
    fun `choosing a provider opens the authorization page as an auth tab watching the redirect scheme`() {
        show()

        val request = startSignIn()

        assertThat(request.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(request.dataString).isEqualTo("https://auth.example.com/authorize?state=state-1")
        assertThat(request.getBooleanExtra(AuthTabIntent.EXTRA_LAUNCH_AUTH_TAB, false)).isTrue()
        assertThat(request.getStringExtra(AuthTabIntent.EXTRA_REDIRECT_SCHEME)).isEqualTo("bookorbit")
        // No browser is known to have Auth Tab here: whatever handles the link gets it.
        assertThat(request.`package`).isNull()
        providerButton().assertIsNotEnabled()
    }

    @Test
    fun `a browser with auth tab is the one asked`() {
        installBrowser(authTab = true)
        show()

        assertThat(startSignIn().`package`).isEqualTo(BrowserPackage)
    }

    @Test
    fun `a browser without auth tab is not singled out`() {
        installBrowser(authTab = false)
        show()

        assertThat(startSignIn().`package`).isNull()
    }

    @Test
    fun `the redirect handed back by the browser completes the sign-in`() {
        installBrowser(authTab = true)
        show()
        val request = startSignIn()

        browserAnswers(request, Activity.RESULT_OK, redirect)

        assertThat(auth.calls).contains("completeOidc $redirect")
        assertThat(auth.calls).doesNotContain("cancelOidc")
        // Signed in: the form stays busy until the screen leaves, so that nobody signs in a second time.
        providerButton().assertIsNotEnabled()
    }

    @Test
    fun `closing the auth tab cancels the sign-in at once`() {
        installBrowser(authTab = true)
        show()
        val request = startSignIn()

        browserAnswers(request, Activity.RESULT_CANCELED)

        compose.onNodeWithText(application.getString(R.string.error_oidc_cancelled)).assertIsDisplayed()
        assertThat(auth.calls).contains("cancelOidc")
        providerButton().assertIsEnabled()
    }

    @Test
    fun `an auth tab that cannot verify the redirect refuses the sign-in`() {
        installBrowser(authTab = true)
        show()
        val request = startSignIn()

        browserAnswers(request, AuthTabIntent.RESULT_VERIFICATION_FAILED)

        compose.onNodeWithText(application.getString(R.string.error_oidc_refused)).assertIsDisplayed()
        assertThat(auth.calls).contains("cancelOidc")
    }

    @Test
    fun `a custom tab closing is not taken for a cancellation - its redirect comes another way`() {
        installBrowser(authTab = false)
        show()
        val request = startSignIn()

        browserAnswers(request, Activity.RESULT_CANCELED)

        assertThat(auth.calls).doesNotContain("cancelOidc")
        providerButton().assertIsNotEnabled()
    }

    @Test
    fun `an auth tab closing is a cancellation even when the browser lost auth tab meanwhile`() {
        installBrowser(authTab = true)
        show()
        val request = startSignIn()
        // The browser was updated, or another became the default, while the tab was open.
        uninstallBrowser()

        browserAnswers(request, Activity.RESULT_CANCELED)

        compose.onNodeWithText(application.getString(R.string.error_oidc_cancelled)).assertIsDisplayed()
        assertThat(auth.calls).contains("cancelOidc")
        providerButton().assertIsEnabled()
    }

    @Test
    fun `a custom tab closing is not a cancellation even when the browser gained auth tab meanwhile`() {
        installBrowser(authTab = false)
        show()
        val request = startSignIn()
        installBrowser(authTab = true)

        browserAnswers(request, Activity.RESULT_CANCELED)

        assertThat(auth.calls).doesNotContain("cancelOidc")
        providerButton().assertIsNotEnabled()
    }

    @Test
    fun `with no remembered server it leaves for the server screen`() {
        auth.serverUrl.value = null
        var changes = 0

        show(onChangeServer = { changes++ })
        compose.waitForIdle()

        assertThat(changes).isEqualTo(1)
    }

    private companion object {
        const val BrowserPackage = "browser.example"
        const val CustomTabsService = "android.support.customtabs.action.CustomTabsService"
    }
}
