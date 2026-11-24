package app.tfdn.lirelle.core.audio.timeline

/** The credits line of the player (spec 5.1), as a structured value the UI renders with its strings. */
sealed interface CreditsLine {
    /** "Authors · read by narrators". */
    data class AuthorsAndNarrators(val authors: Shortened, val narrators: Shortened) : CreditsLine

    /** The authors only: no narrator. */
    data class AuthorsOnly(val authors: Shortened) : CreditsLine

    /** "Read by narrators" only: no author. */
    data class NarratorsOnly(val narrators: Shortened) : CreditsLine

    /** Neither: the line is not shown. */
    data object None : CreditsLine
}

/**
 * A list of names cut down for one line: "[first]", "[first] and [second]", or
 * "[first] and [others] others". [second] is set only for exactly two names; [others] only
 * for three or more.
 */
data class Shortened(val first: String, val second: String?, val others: Int)

/** Spec 5.1, blank names ignored, a name given twice counted once. */
fun creditsOf(authors: List<String>, narrators: List<String>): CreditsLine {
    val writtenBy = shorten(authors)
    val readBy = shorten(narrators)
    return when {
        writtenBy != null && readBy != null -> CreditsLine.AuthorsAndNarrators(writtenBy, readBy)
        writtenBy != null -> CreditsLine.AuthorsOnly(writtenBy)
        readBy != null -> CreditsLine.NarratorsOnly(readBy)
        else -> CreditsLine.None
    }
}

private fun shorten(names: List<String>): Shortened? {
    val kept = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return when (kept.size) {
        0 -> null
        1 -> Shortened(kept[0], null, 0)
        2 -> Shortened(kept[0], kept[1], 0)
        else -> Shortened(kept[0], null, kept.size - 1)
    }
}
