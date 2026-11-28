package app.tfdn.lirelle.feature.login

import android.webkit.CookieManager
import android.webkit.WebStorage
import app.tfdn.lirelle.core.session.ServerKind

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
 * Whether [cookies], a `Cookie` header (`a=1; b=2`), hold the session of a server of [kind], told by its name. An
 * entry without a value is no cookie; a kind whose session is no cookie has none.
 */
internal fun hasSessionCookie(cookies: String?, kind: ServerKind): Boolean {
    val isSession = kind.sessionCookieName ?: return false
    return cookies.orEmpty().split(';').any { '=' in it && isSession(it.substringBefore('=').trim()) }
}

/**
 * The name of Kavita's session cookie: ASP.NET's, whole, or one of the parts it splits a long one into, named
 * after it and numbered (`…CookiesC1`, `…CookiesC2`). The cookies of a sign-in in progress
 * (`.AspNetCore.Correlation.…`, `.AspNetCore.OpenIdConnect.Nonce.…`) are not named so, nor is any other name that
 * only begins the same.
 */
private val KavitaSessionCookie = Regex("""\.AspNetCore\.Cookies(C\d+)?""")

/** Tells the name of the cookie of a session on a server of this kind; null for a kind whose session is no cookie. */
private val ServerKind.sessionCookieName: ((String) -> Boolean)?
    get() = when (this) {
        ServerKind.BookOrbit, ServerKind.Audiobookshelf -> null
        ServerKind.Komga -> { name -> name.startsWith("KOMGA-SESSION") }
        ServerKind.Kavita -> KavitaSessionCookie::matches
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

    /**
     * Whether the WebView holds, among the cookies it sends to [serverUrl], the session of a server of [kind]:
     * what tells a sign-in done on a server whose pages do not say it. Nothing is removed.
     */
    fun hasSessionCookie(kind: ServerKind, serverUrl: String): Boolean =
        hasSessionCookie(store.cookies(serverUrl.asCookieAddress()), kind)

    /** The cookies the WebView sends to [serverUrl], as a `Cookie` header, or null; then everything is removed. */
    fun take(serverUrl: String): String? {
        val cookies = store.cookies(serverUrl.asCookieAddress())
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

/** The server's address as its cookies are asked for: its base path, as a page under it would be. */
private fun String.asCookieAddress(): String = "${trimEnd('/')}/"
