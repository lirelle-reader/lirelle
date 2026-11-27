package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto

/** The oldest Kavita the app works with: the first whose accounts make keys of their own. */
internal const val MinimumKavitaVersion = "0.8.9"

/**
 * The dotted numbers [version] begins with: "0.8.8.3" of "0.8.8.3-beta"; null when it begins with none. Read
 * character by character: however long the server made them, they are gone through without a pattern's recursion.
 */
private fun leadingNumbers(version: String): String? {
    var end = 0
    while (end < version.length && (version[end].isDigit09() || version.partsNumbersAt(end))) end++
    return version.substring(0, end).ifEmpty { null }
}

private fun Char.isDigit09(): Boolean = this in '0'..'9'

/** Whether the character at [index] is a dot between two digits. */
private fun String.partsNumbersAt(index: Int): Boolean =
    this[index] == '.' && getOrNull(index - 1)?.isDigit09() == true && getOrNull(index + 1)?.isDigit09() == true

/**
 * The numbers the version [version] begins with — "0.9.1.4" as it is, 0.8.8.3 of "0.8.8.3-beta" — or null when it
 * begins with none: a build named otherwise ("nightly"), or no version at all. A number too long to be one counts
 * as the greatest.
 */
private fun versionParts(version: String?): List<Int>? =
    leadingNumbers(version.orEmpty().trim())?.split('.')?.map { it.toIntOrNull() ?: Int.MAX_VALUE }

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

/** How much of the numbers a version begins with is told to the user: far more than any version of Kavita has. */
private const val NumbersTold = 32

/** How much of what follows a version's numbers is told to the user: "-beta", a build's name. */
private const val SuffixTold = 16

/**
 * The server's [version] as it is told to the user: the numbers it begins with and the beginning of what follows
 * them ("0.8.8.3-beta" whole), never the whole of a text the server may have made as long as it liked.
 */
internal fun toldKavitaVersion(version: String): String {
    val said = version.trim()
    val numbers = leadingNumbers(said).orEmpty()
    return numbers.take(NumbersTold) + said.substring(numbers.length).take(SuffixTold)
}

/**
 * This account, unless its server is not one the app works with, before anything is made or saved. A server too
 * old is [ApiError.ServerTooOld], with its version as it said it, cut short ([toldKavitaVersion]). One that tells
 * no version in numbers — none, a null, a name — is [ApiError.SignInRefused]: a real Kavita always tells one, and
 * an answer without it is not a server that can be trusted to be recent enough.
 */
internal fun KavitaUserDto.onRecentEnoughServer(): KavitaUserDto {
    val version = kavitaVersion?.takeIf { tellsKavitaVersion(it) } ?: throw ApiErrorException(ApiError.SignInRefused)
    if (isOlderKavita(version)) {
        val tooOld = ApiError.ServerTooOld(ServerKind.Kavita, toldKavitaVersion(version), MinimumKavitaVersion)
        throw ApiErrorException(tooOld)
    }
    return this
}
