package app.tfdn.lirelle.source.audiobookshelf.library

import java.text.Normalizer
import java.util.Locale

/**
 * A text the user typed to narrow a list on the device, looked for inside names as a reader looks: case and accents
 * ignored, so that "reformateurs" finds "Les Réformateurs" and "SAINT-EXUPERY" finds "Saint-Exupéry". The spaces
 * around it are left out; a blank text is none ([of] answers null).
 */
internal class AbsText private constructor(private val folded: String) {

    /** Whether one of [names] holds the text. */
    fun isIn(vararg names: String?): Boolean = names.any { it != null && folded in it.folded() }

    companion object {
        /** The text [typed]; null when it is blank. */
        fun of(typed: String?): AbsText? = typed?.trim()?.takeIf { it.isNotEmpty() }?.let { AbsText(it.folded()) }
    }
}

/** This text without its accents, in lower case. */
private fun String.folded(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Accents, "").lowercase(Locale.ROOT)

/** The marks that decompose from an accented letter. */
private val Accents = Regex("""\p{Mn}+""")
