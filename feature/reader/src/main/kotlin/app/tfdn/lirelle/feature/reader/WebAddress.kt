package app.tfdn.lirelle.feature.reader

import java.net.URI
import java.net.URISyntaxException

private val WebSchemes = setOf("http", "https")

/**
 * [href] when it is a web address a browser may be given, null otherwise. A link of a book can
 * be anything (`javascript:`, `intent:`, `file:`, `content:`...): only a well-formed `http` or
 * `https` address with a host goes through.
 */
internal fun webAddress(href: String): String? {
    val uri = try {
        URI(href)
    } catch (_: URISyntaxException) {
        return null
    }
    val web = uri.scheme?.lowercase() in WebSchemes && !uri.host.isNullOrEmpty() && uri.userInfo == null
    return if (web) uri.toString() else null
}
