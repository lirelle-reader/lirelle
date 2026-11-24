package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.AccountPictures
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Two dots, written plainly or encoded: a path that holds them may climb out of where it says it is. */
private val Climbing = Regex("""(\.|%2e){2}""", RegexOption.IGNORE_CASE)

/**
 * BookOrbit gives the account's picture as a path on its server. Anything else is no address: a whole address,
 * an address without its scheme (`//host/…`), a path that climbs (`..`), or one that reads as another host once
 * put after the profile's address. The server's answers are not trusted with where the profile's token goes.
 */
internal object BookOrbitAccountPictures : AccountPictures {
    override fun pictureUrl(server: String, avatarUrl: String): HttpUrl? {
        val home = server.toHttpUrlOrNull() ?: return null
        val plain = avatarUrl.startsWith("/") && !avatarUrl.startsWith("//") && '\\' !in avatarUrl &&
            !Climbing.containsMatchIn(avatarUrl)
        val url = if (plain) (server.trimEnd('/') + avatarUrl).toHttpUrlOrNull() else null
        return url?.takeIf { it.scheme == home.scheme && it.host == home.host && it.port == home.port }
    }
}
