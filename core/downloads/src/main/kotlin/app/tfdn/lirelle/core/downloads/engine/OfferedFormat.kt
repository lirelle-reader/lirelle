package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind

/**
 * What the book page offers. Sizes are null when the server gave none.
 *
 * @property fileId the file of the text, for the text and the narration; null for an audiobook.
 * @property chapterCount of the audiobook, when the server says.
 * @property estimated true when [estimatedBytes] is not the size of what will be kept but that of a file
 *   about as large: another file of the book, or the file the kept pages are drawn from. It is shown as about
 *   that much.
 * @property textName what the text is named by, from its file's format; of no use to the other formats.
 */
data class OfferedFormat(
    val format: DownloadFormat,
    val fileId: FileId?,
    val estimatedBytes: Long?,
    val chapterCount: Int? = null,
    val estimated: Boolean = false,
    val textName: TextName = TextName.Text,
)

/** What the text among these formats is named by; the text's own name when none of them is the text. */
fun List<OfferedFormat>.textName(): TextName =
    firstOrNull { it.format == DownloadFormat.Text }?.textName ?: TextName.Text

/**
 * The file the book's text is kept of: the file to read, whenever there is one. That is the file the book
 * reader opens (an EPUB, a KEPUB, a MOBI, an AZW, an AZW3, an FB2), kept as it is, or the one the page reader
 * shows: a PDF, kept as it is, a comic or a DjVu, kept as the pages the server serves of it. Null for a book
 * with nothing to read.
 */
val BookDetail.textFile: BookFile? get() = readerFile ?: pagedFile

/**
 * The formats of the book that can be kept, in the order of [DownloadFormat]. The text is the [textFile], when it
 * can be kept.
 *
 * The size of a read-along file is its text and its tracks together: it is shown under the narration only, and the
 * text of such a file, which comes without its audio, has no size known beforehand. A plain EPUB of the same
 * book is about as large: its size is then the estimate. The pages of a comic or of a DjVu have no size known
 * beforehand either: their file's is the estimate.
 */
fun BookDetail.offeredFormats(): List<OfferedFormat> = listOfNotNull(
    textFile?.takeIf { it.keepable }?.let { text(it) },
    audioFiles.takeIf { it.isNotEmpty() }?.let { audio ->
        OfferedFormat(
            format = DownloadFormat.Audiobook,
            fileId = null,
            estimatedBytes = audio.mapNotNull { it.sizeBytes }.takeIf { it.isNotEmpty() }?.sum(),
            chapterCount = audio.firstNotNullOfOrNull { it.chapterCount },
        )
    },
    narrationFile?.let { OfferedFormat(DownloadFormat.Narration, it.id, it.sizeBytes) },
)

private fun BookDetail.text(file: BookFile): OfferedFormat {
    val name = TextName.of(file.format)
    if (servedKind(file) != null) {
        val size = file.sizeBytes
        return OfferedFormat(DownloadFormat.Text, file.id, size, estimated = size != null, textName = name)
    }
    if (!file.hasReadAlong) return OfferedFormat(DownloadFormat.Text, file.id, file.sizeBytes, textName = name)
    val alike = files.firstNotNullOfOrNull { other ->
        other.sizeBytes.takeIf { other.kind == BookFileKind.Text && other.isEpub }
    }
    return OfferedFormat(DownloadFormat.Text, file.id, alike, estimated = alike != null)
}

private val BookDetail.audioFiles: List<BookFile> get() = files.filter { it.kind == BookFileKind.Audio }

/** The read-along file whose narration is kept: the one the reader opens when it is one, else the book's first. */
internal val BookDetail.narrationFile: BookFile?
    get() = readerFile?.takeIf { it.hasReadAlong } ?: files.firstOrNull { it.hasReadAlong }
