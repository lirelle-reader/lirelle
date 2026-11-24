package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.fileName
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.pagedKindOf

private const val EpubSuffix = ".epub"
private const val AudioSuffix = ".audio"
private const val TrackPositionDigits = 3

/** Short enough for any file system once the position is before it; the end is kept, with the extension. */
private const val SafeLength = 100
private val Unsafe = Regex("[^A-Za-z0-9._-]")

/** The name by which the text and the narration know the same EPUB. */
internal fun epubName(fileId: FileId): String = "${fileId.fileName}$EpubSuffix"

/**
 * The name of the book's text on the device: the file name of its file's id, then its own extension. An EPUB
 * and a KEPUB are both `"<fileId>.epub"`, the name the text and the narration share; a file of another format
 * is named after its format, in lower case (`"12.pdf"`, `"12.azw3"`). The name is only a place: which file the
 * text is, its source says ([wholeFileOf]).
 */
internal fun textName(file: BookFile): String =
    if (file.isEpub) epubName(file.id) else "${file.id.fileName}.${safe(file.format.lowercase())}"

/** What the file of the book's text is checked and opened as. */
internal fun textKind(file: BookFile): FileKind = when {
    file.isEpub -> FileKind.Epub
    pagedKindOf(file.format) == PagedKind.Pdf -> FileKind.Pdf
    else -> FileKind.Book
}

/**
 * What the pages of [file] are when the server serves it by pages, a comic or a DjVu: its text is then kept
 * as those pages. Null for a file kept as it is, a PDF among them.
 */
internal fun servedKind(file: BookFile): PagedKind? = pagedKindOf(file.format)?.takeIf { it != PagedKind.Pdf }

/**
 * The book's file a text kept as a single file is drawn from, whatever its name: the file itself, or its EPUB
 * without audio. Null for a page, and for any file that is not a book's text.
 */
internal fun wholeFileOf(file: PlannedFile): FileId? = when (val source = file.source) {
    is FileSource.Whole -> source.fileId.takeIf { file.kind.isText }
    is FileSource.Audioless -> source.fileId.takeIf { file.kind.isText }
    else -> null
}

/** Stable for the same asset; the sequence makes it unique within the audiobook. */
internal fun assetName(sequence: Int, assetId: String): String = audioName("$sequence-${safe(assetId)}")

/**
 * Stable for the same track of the same map: its position among the map's files, then its path in the EPUB, the
 * extension kept.
 */
internal fun trackName(position: Int, href: String): String =
    audioName("${position.toString().padStart(TrackPositionDigits, '0')}-${safe(href)}")

/** A single path segment of letters, digits, `.`, `-` and `_`. */
private fun safe(text: String): String = Unsafe.replace(text, "_").takeLast(SafeLength)

/** Only an EPUB ends in `.epub`: the file store shares those between the text and the narration. */
private fun audioName(name: String): String =
    if (name.endsWith(EpubSuffix, ignoreCase = true)) name + AudioSuffix else name
