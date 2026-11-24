package app.tfdn.lirelle.core.library.model

private val EpubFormats = setOf("EPUB", "KEPUB")

/** The formats the book reader lays out, in the order the app prefers them. */
private val ReflowableFormats = listOf("EPUB", "KEPUB", "MOBI", "AZW3", "AZW", "FB2")

/** The formats the page reader shows, in the order the app prefers them, each with its kind. */
private val PagedFormats = mapOf(
    "PDF" to PagedKind.Pdf,
    "CBZ" to PagedKind.Comic,
    "CBR" to PagedKind.Comic,
    "CB7" to PagedKind.Comic,
    "DJVU" to PagedKind.Djvu,
)

/** A reflowable file comes before a paged one. */
private val ReadFormats = ReflowableFormats + PagedFormats.keys

/** Whether a file of this format is an EPUB, a KEPUB included. */
fun isEpubFormat(format: String?): Boolean = format?.uppercase() in EpubFormats

/** Whether a file of this format opens in the book reader, which lays its text out. */
fun isReflowableFormat(format: String?): Boolean = format?.uppercase() in ReflowableFormats

/** The kind of pages a file of this format has; null when it is not read by pages. */
fun pagedKindOf(format: String?): PagedKind? = PagedFormats[format?.uppercase()]

/**
 * The one file of a book the app reads, among its content [files]: the one the server calls primary when the
 * app reads its format; else the first file of the first format the book has, the reflowable ones (EPUB,
 * KEPUB, MOBI, AZW3, AZW, FB2) before the paged ones (PDF, CBZ, CBR, CB7, DJVU), in the server's order within
 * a format. Null when the book has nothing the app reads. The book's page and its card choose with this.
 */
fun <T> fileToRead(files: List<T>, format: (T) -> String?, isPrimary: (T) -> Boolean): T? {
    val primary = files.firstOrNull(isPrimary)
    if (primary != null && format(primary)?.uppercase() in ReadFormats) return primary
    return ReadFormats.firstNotNullOfOrNull { wanted -> files.firstOrNull { format(it)?.uppercase() == wanted } }
}
