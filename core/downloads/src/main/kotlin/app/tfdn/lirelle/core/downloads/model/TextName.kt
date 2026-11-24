package app.tfdn.lirelle.core.downloads.model

/**
 * What the text kept of a book is named by, where a kept format is named. A book read by pages is named by
 * its file's format; a reflowable text (EPUB, KEPUB, MOBI, AZW, AZW3, FB2) is the [Text].
 */
enum class TextName {
    Text,
    Pdf,
    Cbz,
    Cbr,
    Cb7,
    Djvu,
    ;

    companion object {
        /** The name for a file of this [format], as the server writes it, in any case; [Text] when unknown. */
        fun of(format: String?): TextName =
            entries.firstOrNull { it != Text && it.name.equals(format, ignoreCase = true) } ?: Text
    }
}
