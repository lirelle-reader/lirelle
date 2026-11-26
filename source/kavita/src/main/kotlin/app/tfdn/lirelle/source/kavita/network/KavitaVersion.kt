package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto

/** The oldest Kavita the app works with: the first whose accounts make keys of their own. */
internal const val MinimumKavitaVersion = "0.8.9"

/** The dotted numbers a version begins with: "0.8.8.3" of "0.8.8.3-beta". */
private val LeadingNumbers = Regex("""^\d+(\.\d+)*""")

/**
 * The numbers the version [version] begins with — "0.9.1.4" as it is, 0.8.8.3 of "0.8.8.3-beta" — or null when it
 * begins with none: a build named otherwise ("nightly"), or no version at all. A number too long to be one counts
 * as the greatest.
 */
private fun versionParts(version: String?): List<Int>? =
    LeadingNumbers.find(version.orEmpty().trim())?.value?.split('.')?.map { it.toIntOrNull() ?: Int.MAX_VALUE }

/** Whether [version] begins with numbers, as every version of Kavita does: what can be compared at all. */
internal fun tellsKavitaVersion(version: String?): Boolean = versionParts(version) != null

/**
 * Whether the server's [version] is older than [minimum], by the numbers each begins with, number by number, a
 * missing one being 0 ("1.0" is "1.0.0"). A version that begins with no number is not known to be older: whoever
 * asks first makes sure it is one ([tellsKavitaVersion]).
 */
internal fun isOlderKavita(version: String, minimum: String = MinimumKavitaVersion): Boolean {
    val running = versionParts(version) ?: return false
    val needed = versionParts(minimum) ?: return false
    for (index in 0 until maxOf(running.size, needed.size)) {
        val difference = running.getOrElse(index) { 0 }.compareTo(needed.getOrElse(index) { 0 })
        if (difference != 0) return difference < 0
    }
    return false
}

/**
 * This account, unless its server is not one the app works with, before anything is made or saved. A server too
 * old is [ApiError.ServerTooOld], with its version as it said it. One that tells no version in numbers — none, a
 * null, a name — is [ApiError.SignInRefused]: a real Kavita always tells one, and an answer without it is not a
 * server that can be trusted to be recent enough.
 */
internal fun KavitaUserDto.onRecentEnoughServer(): KavitaUserDto {
    val version = kavitaVersion?.takeIf { tellsKavitaVersion(it) } ?: throw ApiErrorException(ApiError.SignInRefused)
    if (isOlderKavita(version)) throw ApiErrorException(ApiError.ServerTooOld(version, MinimumKavitaVersion))
    return this
}
