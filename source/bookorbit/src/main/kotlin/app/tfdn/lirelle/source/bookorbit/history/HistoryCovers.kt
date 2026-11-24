package app.tfdn.lirelle.source.bookorbit.history

import java.net.URLEncoder

/**
 * The cover URLs of the history's books, against the session's server. The cover version is part of the
 * URL, so a changed cover is a new cache entry. Rows use the `thumbnail` image, the detail header `cover`.
 */
internal class HistoryCovers(private val serverUrl: String) {

    /** The [image] of the book BookOrbit numbers [bookNumber], whose version is [version]. */
    fun url(bookNumber: Long, image: String, version: String?): String =
        "$serverUrl/api/v1/books/$bookNumber/$image" +
            (version?.takeIf { it.isNotEmpty() }?.let { "?t=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: "")
}
