package app.tfdn.lirelle.feature.login

import java.net.URI

/**
 * The WebViews' cookie store and their pages' storage as far as these tests need them: a cookie per host, path
 * and name, sent to an address whose path is under its own, the longest paths first; clearing removes them all,
 * the pages' storage with them.
 */
internal class FakeWebCookies : WebCookies {
    private data class Key(val host: String, val path: String, val name: String)

    private val held = linkedMapOf<Key, String>()

    /** How many times everything was removed. */
    var clearings = 0
        private set

    /** No cookie is held. */
    val isEmpty: Boolean get() = held.isEmpty()

    fun add(host: String, path: String, name: String, value: String) {
        held[Key(host, path, name)] = value
    }

    override fun cookies(url: String): String? {
        val uri = URI(url)
        return held.entries
            .filter { (key) -> key.host == uri.host && pathMatches(uri.path.ifEmpty { "/" }, key.path) }
            .sortedByDescending { (key) -> key.path.length }
            .joinToString("; ") { (key, value) -> "${key.name}=$value" }
            .ifEmpty { null }
    }

    override fun clearAll() {
        held.clear()
        clearings++
    }

    /** RFC 6265's path-match: the same path, or one under the cookie's. */
    private fun pathMatches(requestPath: String, cookiePath: String): Boolean = requestPath == cookiePath ||
        requestPath.startsWith(cookiePath) && (cookiePath.endsWith('/') || requestPath[cookiePath.length] == '/')
}
