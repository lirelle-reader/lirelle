package app.tfdn.lirelle.core.ids

private const val Escape = '~'
private const val HexDigits = "0123456789ABCDEF"
private const val ByteMask = 0xFF
private const val HighNibble = 4
private const val LowNibbleMask = 0x0F

/**
 * The name of a file or folder made from an id's [text], whatever the text holds: an id comes from a server, and
 * must never make a path. Letters, digits, `-` and `_` are kept, so a BookOrbit number is its own name; any other
 * character, the escape `~` included, is written as `~XX` per byte of its UTF-8 form, in upper-case hex. The name
 * thus never holds a separator or a dot, and two ids never share a name (a lone UTF-16 surrogate, which no well-formed
 * id holds, is written as the replacement character).
 *
 * An empty id is a bug of the caller and throws [IllegalArgumentException].
 */
fun fileNameOf(text: String): String {
    require(text.isNotEmpty()) { "An empty id has no file name" }
    if (text.all(::isKept)) return text
    return buildString {
        var start = 0
        while (start < text.length) {
            var end = start
            val kept = isKept(text[start])
            while (end < text.length && isKept(text[end]) == kept) end++
            val run = text.substring(start, end)
            // A whole run is encoded at once, so that a character made of two UTF-16 units stays one code point.
            if (kept) append(run) else run.encodeToByteArray().forEach { appendEscaped(it.toInt() and ByteMask) }
            start = end
        }
    }
}

private fun isKept(char: Char): Boolean =
    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_'

private fun StringBuilder.appendEscaped(byte: Int) {
    append(Escape)
    append(HexDigits[byte shr HighNibble])
    append(HexDigits[byte and LowNibbleMask])
}

/** The file name of this book's id (see [fileNameOf]). */
val BookId.fileName: String get() = fileNameOf(value)

/** The file name of this file's id (see [fileNameOf]). */
val FileId.fileName: String get() = fileNameOf(value)

/** The file name of this series' id (see [fileNameOf]). */
val SeriesId.fileName: String get() = fileNameOf(value)

/** The file name of this author's id (see [fileNameOf]). */
val AuthorId.fileName: String get() = fileNameOf(value)

/** The file name of this shelf's id (see [fileNameOf]). */
val ShelfId.fileName: String get() = fileNameOf(value)

/** The file name of this annotation's id (see [fileNameOf]). */
val MarkId.fileName: String get() = fileNameOf(value)

/** The file name of this finished reading's id (see [fileNameOf]). */
val ReadId.fileName: String get() = fileNameOf(value)
