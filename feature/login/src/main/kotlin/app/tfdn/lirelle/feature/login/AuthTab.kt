package app.tfdn.lirelle.feature.login

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.core.net.toUri

/** Scheme of the only redirect URI the BookOrbit server accepts, `bookorbit://oauth2-callback`. */
internal const val OidcRedirectScheme = "bookorbit"

/**
 * The package of the default browser when it can open an Auth Tab, else null.
 *
 * An Auth Tab watches for the redirect itself and hands it back as an activity result. A
 * plain Custom Tab has to navigate to the `bookorbit:` link instead, which Chromium blocks
 * when the provider's page gets there by script, as Authentik's does.
 */
internal fun authTabBrowser(context: Context): String? =
    CustomTabsClient.getPackageName(context, emptyList())
        ?.takeIf { CustomTabsClient.isAuthTabSupported(context, it) }

/**
 * Opens [url] for sign-in: in an Auth Tab of [authTabBrowser] when there is one, else in
 * whatever answers, where browsers without Auth Tab show a Custom Tab. Returns false when
 * nothing on the device can open [url].
 */
internal fun openForSignIn(launcher: ActivityResultLauncher<Intent>, url: String, authTabBrowser: String?): Boolean {
    val authTab = AuthTabIntent.Builder().build()
    // The browser that was checked is the one that must answer, not another handler of the link.
    authTabBrowser?.let(authTab.intent::setPackage)
    return try {
        authTab.launch(launcher, url.toUri(), OidcRedirectScheme)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * What the activity result of [openForSignIn] means, or null when it means nothing.
 *
 * [fromAuthTab] tells whether the browser was opened as an Auth Tab. Otherwise it showed a
 * Custom Tab, whose result is "cancelled" whatever happened, even when its redirect reached
 * the app through the intent filter: only a redirect URI or an Auth Tab's own failure codes
 * are believed from it.
 */
internal fun authTabResultOf(resultCode: Int, redirectUri: String?, fromAuthTab: Boolean): AuthTabResult? = when {
    resultCode == AuthTabIntent.RESULT_OK && redirectUri != null -> AuthTabResult.Redirected(redirectUri)
    resultCode == AuthTabIntent.RESULT_VERIFICATION_FAILED -> AuthTabResult.Failed
    resultCode == AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> AuthTabResult.Failed
    !fromAuthTab -> null
    resultCode == AuthTabIntent.RESULT_CANCELED -> AuthTabResult.Cancelled
    else -> AuthTabResult.Failed
}
