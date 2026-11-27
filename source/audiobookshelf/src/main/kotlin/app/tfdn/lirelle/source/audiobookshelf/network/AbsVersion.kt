package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.session.ServerKind

/** The oldest Audiobookshelf the app works with, as the user is told it: 2.26.0, the first with refresh tokens. */
internal const val MinimumAbsVersion = "2.26"

/** How much of the numbers a version begins with is told to the user: far more than any version has. */
private const val NumbersTold = 32

/** How much of what follows a version's numbers is told to the user: "-beta", a build's name. */
private const val SuffixTold = 16

/**
 * A version of Audiobookshelf, by the dotted numbers it begins with — 2.38.0 of "2.38.0" and of "2.38.0-beta" —
 * compared number by number, a missing one being 0 ("2.26" is "2.26.0"). A number too long to be one counts as
 * the greatest.
 */
internal class AbsVersion private constructor(private val parts: List<Int>) : Comparable<AbsVersion> {

    override fun compareTo(other: AbsVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** The oldest version the app works with. */
        val Minimum: AbsVersion = requireNotNull(parse(MinimumAbsVersion))

        /** The version [text] begins with; null when it begins with no number: a build named otherwise, or none. */
        fun parse(text: String?): AbsVersion? =
            leadingNumbers(text.orEmpty().trim())?.split('.')?.map { it.toIntOrNull() ?: Int.MAX_VALUE }
                ?.let(::AbsVersion)
    }
}

/**
 * Nothing, when the server's [version] is one the app works with; [ApiError.ServerTooOld] otherwise, with the version
 * as the server said it, cut short. A version that begins with no number — none, a name — is refused the same way:
 * every Audiobookshelf the app works with tells its version in numbers.
 */
internal fun requireRecentAbs(version: String?) {
    val parsed = AbsVersion.parse(version)
    if (parsed == null || parsed < AbsVersion.Minimum) {
        throw ApiErrorException(
            ApiError.ServerTooOld(ServerKind.Audiobookshelf, toldAbsVersion(version.orEmpty()), MinimumAbsVersion),
        )
    }
}

/**
 * The server's [version] as it is told to the user: the numbers it begins with and the beginning of what follows
 * them, never the whole of a text the server may have made as long as it liked.
 */
private fun toldAbsVersion(version: String): String {
    val said = version.trim()
    val numbers = leadingNumbers(said).orEmpty()
    return numbers.take(NumbersTold) + said.substring(numbers.length).take(SuffixTold)
}

/**
 * The dotted numbers [version] begins with: "2.38.0" of "2.38.0-beta"; null when it begins with none. Read character
 * by character: however long the server made them, they are gone through without a pattern's recursion.
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
