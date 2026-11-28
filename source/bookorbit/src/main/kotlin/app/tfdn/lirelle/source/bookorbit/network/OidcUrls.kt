package app.tfdn.lirelle.source.bookorbit.network

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The only redirect URI the BookOrbit server accepts from native clients. */
internal const val OidcRedirectUri = "bookorbit://oauth2-callback"

/** The scheme of [OidcRedirectUri], which the browser's Auth Tab watches for. */
internal const val OidcRedirectScheme = "bookorbit"

// The six parameters are the OAuth authorization request itself.
@Suppress("LongParameterList")
internal fun buildAuthorizationUrl(
    authorizationEndpoint: String,
    clientId: String,
    scopes: String,
    state: String,
    nonce: String,
    codeChallenge: String,
): String = authorizationEndpoint.toHttpUrl().newBuilder()
    .addQueryParameter("response_type", "code")
    .addQueryParameter("client_id", clientId)
    .addQueryParameter("redirect_uri", OidcRedirectUri)
    .addQueryParameter("scope", scopes)
    .addQueryParameter("code_challenge", codeChallenge)
    .addQueryParameter("code_challenge_method", "S256")
    .addQueryParameter("state", state)
    .addQueryParameter("nonce", nonce)
    .build()
    .toString()

/** What the identity provider sent back to [OidcRedirectUri]. */
internal sealed interface OidcRedirect {
    /**
     * The state of the sign-in this answers, as the provider gives it back; null when it names none. It tells
     * the answer of the sign-in that waits from a link sent by anything else.
     */
    val state: String?

    data class Code(val code: String, override val state: String) : OidcRedirect

    /** An answer to the sign-in begun with [state] that gives no code to exchange: the provider refused it. */
    data class Denied(override val state: String) : OidcRedirect

    /** Not a redirect that can be read, or one that names no sign-in. */
    data object Refused : OidcRedirect {
        override val state: String? get() = null
    }

    companion object {
        /** Anything can send this URI to the app, so whatever cannot be read is [Refused]. */
        fun parse(uri: String): OidcRedirect {
            // The fragment goes first: a `?` after `#` belongs to it, not to the query.
            val withoutFragment = uri.substringBefore('#')
            if (withoutFragment.substringBefore('?') != OidcRedirectUri) return Refused
            val params = try {
                withoutFragment.substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate { pair ->
                        val key = pair.substringBefore('=')
                        val value = pair.substringAfter('=', "")
                        URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
                    }
            } catch (_: IllegalArgumentException) {
                // A broken escape such as `%zz`.
                return Refused
            }
            val code = params["code"]
            val state = params["state"]
            return when {
                state.isNullOrEmpty() -> Refused
                "error" in params || code.isNullOrEmpty() -> Denied(state)
                else -> Code(code, state)
            }
        }
    }
}
