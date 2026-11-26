package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.reader.marks.spineIndexOf

private val Wrapped = Regex("""epubcfi\((.*)\)""")

/** A step of a path with the id of its element, as the reader writes it: `/4[id]`; `^` escapes what follows. */
private val StepWithId = Regex("""/\d+\[((?:\^.|[^\]^])*)]""")

/** An escaped character of an id. */
private val Escaped = Regex("""\^(.)""")

/**
 * The sentence the book's page marker [cfi] is in, told from the marker alone: the place the reader keeps of a
 * book, a path into one of its sections that names the elements it goes through by their ids. A narrated
 * sentence is such an element, so a marker inside a sentence names it; a marker in a section without narration
 * is at the first sentence of the next narrated one.
 *
 * Null when the marker names no sentence: it stands between two sentences of a narrated section, or in words
 * that are not narrated (a title, a caption), or it is past the narration, or it is not a path at all. Which
 * sentence comes first after such a place is in the section's page — the order of its elements — which only the
 * reader reads; nothing kept with the position says it. The caller must not take the section's start instead.
 */
fun ReadAlongMap.sentenceAtMarker(cfi: String): ReadAlongMap.Sentence? {
    val section = spineIndexOf(cfi) ?: return null
    if (fragmentsOf(section).isEmpty()) return firstSentenceFrom(section)
    // The innermost first: a sentence inside a paragraph that has an id too.
    return markerIds(cfi).asReversed().firstNotNullOfOrNull { sentenceOf(section, it) }
}

/**
 * The ids of the elements the marker [cfi] goes through inside its section, outermost first; those of a range
 * (a page) are its start's. The words a marker may quote after a character offset are not ids.
 */
internal fun markerIds(cfi: String): List<String> {
    val inner = Wrapped.matchEntire(cfi.trim())?.groupValues?.get(1) ?: return emptyList()
    val parts = splitRange(inner)
    val start = if (parts.size > 1) parts[0] + parts[1] else parts[0]
    val inSection = start.substringAfter('!', missingDelimiterValue = "")
    return StepWithId.findAll(inSection)
        // A parameter may follow the id: `[id;s=b]`.
        .map { splitAt(it.groupValues[1], ';').first().replace(Escaped, "$1") }
        .filter { it.isNotEmpty() }
        .toList()
}

/** The parent, the start and the end of a range, or the one path of a marker that is none. */
private fun splitRange(inner: String): List<String> = splitAt(inner, ',', outsideBrackets = true)

/** [text] cut at each [separator] that is not escaped, nor inside brackets when [outsideBrackets]. */
private fun splitAt(text: String, separator: Char, outsideBrackets: Boolean = false): List<String> {
    val parts = mutableListOf(StringBuilder())
    var inBrackets = false
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '^' && index + 1 < text.length -> parts.last().append(char).append(text[++index])
            char == separator && !(outsideBrackets && inBrackets) -> parts += StringBuilder()
            else -> {
                if (char == '[') inBrackets = true else if (char == ']') inBrackets = false
                parts.last().append(char)
            }
        }
        index++
    }
    return parts.map { it.toString() }
}
