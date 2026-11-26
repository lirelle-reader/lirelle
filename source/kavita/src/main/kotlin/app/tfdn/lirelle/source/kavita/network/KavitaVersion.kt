package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto

/** The oldest Kavita the app works with: the first whose accounts make keys of their own. */
internal const val MinimumKavitaVersion = "0.8.9"

/**
 * The numbers of the dotted version [version] ("0.9.1.4"), or null when it is not one: a build named otherwise
 * ("nightly"), or no version at all.
 */
private fun versionParts(version: String): List<Int>? {
    val parts = version.trim().split('.')
    if (parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) return null
    val numbers = parts.mapNotNull { it.toIntOrNull() }
    return numbers.takeIf { it.size == parts.size }
}

/**
 * Whether the server's [version] is older than [minimum], number by number, a missing one being 0 ("1.0" is
 * "1.0.0"). A version that is not dotted numbers is not known to be older: it is let through.
 */
internal fun isOlderKavita(version: String, minimum: String = MinimumKavitaVersion): Boolean {
    val running = versionParts(version) ?: return false
    val needed = versionParts(minimum) ?: return false
    for (index in 0 until maxOf(running.size, needed.size)) {
        val difference = running.getOrElse(index) { 0 } - needed.getOrElse(index) { 0 }
        if (difference != 0) return difference < 0
    }
    return false
}

/** This account, unless its server is too old for the app: [ApiError.ServerTooOld] then, before anything is made. */
internal fun KavitaUserDto.onRecentEnoughServer(): KavitaUserDto {
    if (isOlderKavita(kavitaVersion)) {
        throw ApiErrorException(ApiError.ServerTooOld(kavitaVersion, MinimumKavitaVersion))
    }
    return this
}
