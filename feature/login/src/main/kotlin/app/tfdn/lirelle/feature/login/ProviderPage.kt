package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.session.ServerKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The page of a provider that signs in through a WebView of the app, as the sign-in opens it.
 *
 * @property serverUrl the server signed in to, whose own page ends the provider's flow.
 * @property kind the kind of [serverUrl], which finishes the sign-in.
 * @property address where the page begins: the server's address for the provider's flow.
 * @property providerName the provider's name, as the sign-in screen offered it.
 */
data class ProviderPage(
    val serverUrl: String,
    val kind: ServerKind,
    val address: String,
    val providerName: String,
)

/** How a provider's page ended, as its screen tells it. */
sealed interface ProviderPageEnd {
    /**
     * The page reached the server's own, signed in: [cookies] are those the WebView held for the server, as it
     * would send them there.
     */
    data class SignedIn(val cookies: String) : ProviderPageEnd {
        /** Never prints the cookies: they carry a session. */
        override fun toString(): String = "SignedIn(cookies=<redacted>)"
    }

    /** The server refused the sign-in: its login page, with an error. */
    data object Refused : ProviderPageEnd

    /** The flow led to a page in cleartext outside the server, which was not shown. */
    data object Unencrypted : ProviderPageEnd

    /** The user closed the page. */
    data object Closed : ProviderPageEnd
}

/**
 * What a page the provider's flow led to says of the sign-in: done, refused by the server, or ended on a page in
 * cleartext outside the server ([Unencrypted]), which is not shown.
 */
enum class ProviderPageOutcome { SignedIn, Refused, Unencrypted }

/**
 * What the page at [url] says of a provider's sign-in to [serverUrl], a server of [kind], or null while the flow
 * goes on. Each kind's server ends it its own way, on its own pages only — same scheme, host and port: a page of
 * the provider's, or of another server, ends nothing. [hasSessionCookie] tells whether the WebView holds the
 * server's session, for a kind whose pages do not say it; it is asked on the server's own pages only.
 *
 * A page in cleartext outside the server ends the sign-in ([ProviderPageOutcome.Unencrypted]), whatever the kind:
 * a password typed there could be read on its way, and the page changed. Only the server's own may be in
 * cleartext — the user accepted it on the Server screen. Only the pages the WebView goes to are seen here, not what
 * it sends: a form of a secure page that posts to a cleartext address is not caught — the WebView does not ask
 * about a POST, and the page it leads to is seen once the form was sent.
 */
internal fun providerPageOutcome(
    kind: ServerKind,
    serverUrl: String,
    url: String,
    hasSessionCookie: () -> Boolean,
): ProviderPageOutcome? {
    val server = serverUrl.toHttpUrlOrNull()
    val page = url.toHttpUrlOrNull()
    if (server == null || page == null) return null
    if (page.scheme != server.scheme || page.host != server.host || page.port != server.port) {
        return if (page.isHttps) null else ProviderPageOutcome.Unencrypted
    }
    return when (kind) {
        ServerKind.Komga -> komgaPageOutcome(server, page)
        ServerKind.Kavita -> kavitaPageOutcome(server, page, hasSessionCookie)
        // BookOrbit's providers sign in through the browser: it never opens this page.
        ServerKind.BookOrbit -> null
    }
}

/** The query Komga adds to the page it sends the WebView to once the provider is done: `server_redirect=Y`. */
private const val ServerRedirect = "server_redirect"

/**
 * Komga's end, on its own [page]: once the provider is done, the [server] sends the WebView to its own page, under
 * its base path: `{base}/?server_redirect=Y` when signed in, `{base}/login?server_redirect=Y&error=…` when it
 * refused (its message is not shown).
 */
private fun komgaPageOutcome(server: HttpUrl, page: HttpUrl): ProviderPageOutcome? {
    if (page.queryParameter(ServerRedirect) != "Y") return null
    val base = server.encodedPath.trimEnd('/')
    return when (page.encodedPath.trimEnd('/')) {
        base -> ProviderPageOutcome.SignedIn
        "$base/login" -> ProviderPageOutcome.Refused
        else -> null
    }
}

/** The query Kavita's login page carries when the provider's sign-in was refused, with its message. */
private const val KavitaError = "error"

/** Kavita's login page, under its base path. */
private const val KavitaLogin = "/login"

/**
 * The addresses the provider's flow goes through on Kavita, under its base path: where it begins, and where the
 * provider comes back to. Never an end, whatever the WebView holds by then.
 */
private val KavitaFlow = setOf("/oidc/login", "/signin-oidc")

/**
 * Kavita's end, on its own [page]: its pages are an application whose address says nothing of the sign-in. Under
 * the [server]'s base path, the login page with an `error` is the sign-in refused (its message is not shown),
 * looked at first: a session left by an earlier page would not make it one that succeeded. Any other page, once
 * the session's cookie is held ([hasSessionCookie]), is the sign-in done — but the addresses of the flow itself,
 * which end nothing. The paths are compared whatever their case.
 */
private fun kavitaPageOutcome(server: HttpUrl, page: HttpUrl, hasSessionCookie: () -> Boolean): ProviderPageOutcome? {
    val base = server.encodedPath.trimEnd('/')
    val path = page.encodedPath.trimEnd('/')
    val underBase = path.length == base.length || path.getOrNull(base.length) == '/'
    if (!path.startsWith(base, ignoreCase = true) || !underBase) return null
    val own = path.substring(base.length).lowercase()
    return when {
        own in KavitaFlow -> null
        own == KavitaLogin && KavitaError in page.queryParameterNames -> ProviderPageOutcome.Refused
        hasSessionCookie() -> ProviderPageOutcome.SignedIn
        else -> null
    }
}

/** The host of the page at [url], as the bar shows it (an internationalised name in its ASCII form); null if none. */
internal fun pageHost(url: String): String? = url.toHttpUrlOrNull()?.host
