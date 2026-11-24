package app.tfdn.lirelle.feature.login

import android.webkit.CookieManager
import android.webkit.WebStorage

/** The WebViews' cookie store and their pages' storage, as far as a sign-in needs them. */
interface WebCookies {
    /** The cookies sent to [url], as a `Cookie` header (`a=1; b=2`), the longest paths first; null when none. */
    fun cookies(url: String): String?

    /** Removes every cookie and everything pages kept in their storage, and writes the store to disk. */
    fun clearAll()
}

/**
 * The store every WebView of the app shares, and the pages' storage. Only the provider's pages keep anything
 * there: the reader's WebView answers each of its requests itself, from the app, and keeps no storage.
 */
internal object AndroidWebCookies : WebCookies {
    override fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

    override fun clearAll() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }
}

/**
 * What a provider's page leaves in the WebViews: the cookies of the server signed in to, which carry the session
 * its sign-in opened, and those of the provider, which carry the account's session there, with whatever its
 * pages kept in their storage. The server's session is read as a WebView sends it to the server's address — its
 * base path included, so that a cookie of that path is among them — and handed to the server's kind; then
 * everything goes. Kept, the provider's session would sign the next account added on that server in as the same
 * one, without asking, and stay on the device.
 */
class ProviderPageCookies(private val store: WebCookies) {

    /** The cookies the WebView sends to [serverUrl], as a `Cookie` header, or null; then everything is removed. */
    fun take(serverUrl: String): String? {
        val cookies = store.cookies("${serverUrl.trimEnd('/')}/")
        forgetAll()
        return cookies
    }

    /** Removes every cookie, whoever set it, and the pages' storage. */
    fun forgetAll() = store.clearAll()

    /** How the page ends on [outcome]: with the server's cookies when signed in ([take]); everything goes. */
    fun endOn(outcome: ProviderPageOutcome, serverUrl: String): ProviderPageEnd = when (outcome) {
        ProviderPageOutcome.SignedIn -> ProviderPageEnd.SignedIn(take(serverUrl).orEmpty())
        ProviderPageOutcome.Refused -> ProviderPageEnd.Refused.also { forgetAll() }
        ProviderPageOutcome.Unencrypted -> ProviderPageEnd.Unencrypted.also { forgetAll() }
    }
}
