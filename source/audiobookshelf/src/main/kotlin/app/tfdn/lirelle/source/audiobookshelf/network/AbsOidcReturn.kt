package app.tfdn.lirelle.source.audiobookshelf.network

import java.net.URLDecoder

/**
 * What the server's `mobile-redirect` gives the code it took from the provider when there is none: the provider's
 * refusal is not passed on, and the code is written as JavaScript writes nothing.
 */
private const val NoCode = "undefined"

/** What came back from Audiobookshelf's provider, through the server, to the address a sign-in returns to. */
internal sealed interface AbsOidcReturn {
    /**
     * The state of the sign-in this answers, as it comes back; null when it names none. It tells the return of the
     * sign-in that waits from a link sent by anything else.
     */
    val state: String?

    data class Code(val code: String, override val state: String) : AbsOidcReturn

    /** A return to the sign-in begun with [state] that gives no code to exchange: the provider refused it. */
    data class Denied(override val state: String) : AbsOidcReturn

    /** Not a return that can be read, not to the sign-in's address, or one that names no sign-in. */
    data object Refused : AbsOidcReturn {
        override val state: String? get() = null
    }

    companion object {
        /**
         * Reads [uri] as a return to [address], the one the sign-in that waits returns to: what comes before its
         * query must be [address], whole — no path, no user, no port more — as BookOrbit compares its own. Anything
         * can send the app a link, so whatever cannot be read is [Refused].
         */
        fun parse(uri: String, address: String): AbsOidcReturn {
            if (!uri.isAt(address)) return Refused
            val params = queryOf(uri) ?: return Refused
            val code = params["code"]
            val state = params["state"]
            return when {
                state.isNullOrEmpty() -> Refused
                "error" in params || code.isNullOrEmpty() || code == NoCode -> Denied(state)
                else -> Code(code, state)
            }
        }
    }
}

/**
 * Whether this link is to [address], whole, before its query; its fragment, which comes after, set aside. Its case
 * aside too: a scheme and a host are the same in any case, and the address has nothing else.
 */
private fun String.isAt(address: String): Boolean =
    substringBefore('#').substringBefore('?').equals(address, ignoreCase = true)

/** The parameters of the query of [uri], decoded; null when one cannot be (a broken escape such as `%zz`). */
private fun queryOf(uri: String): Map<String, String>? {
    // The fragment goes first: a `?` after `#` belongs to it, not to the query.
    val query = uri.substringBefore('#').substringAfter('?', "")
    return try {
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}
