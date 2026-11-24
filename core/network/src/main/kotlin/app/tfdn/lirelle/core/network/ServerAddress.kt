package app.tfdn.lirelle.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Result of normalising what the user typed as the server address. */
sealed interface ServerAddress {

    /** [url] has a scheme and no trailing slash. [isCleartext] is true for `http`. */
    data class Valid(val url: String, val isCleartext: Boolean) : ServerAddress

    data object Invalid : ServerAddress

    companion object {
        private val Scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        fun parse(input: String): ServerAddress {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return Invalid
            // Only a scheme at the very start counts: `host/path?next=http://x` has none.
            val withScheme = if (Scheme.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
            // HttpUrl only parses http and https, which rejects every other scheme.
            val parsed = withScheme.toHttpUrlOrNull() ?: return Invalid
            // The address is stored as it is and shown on screen: no credentials in it.
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return Invalid
            val normalised = parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
            return Valid(normalised, isCleartext = !parsed.isHttps)
        }
    }
}
