// The file also holds the functions that read and write the ids, hence its name.
@file:Suppress("MatchingDeclarationName")

package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.FileDto

/**
 * What a Kavita file id carries: the ids Kavita's progress is written with (library, series, volume, chapter),
 * and the file's own. Written `library-series-volume-chapter-file`, opaque to the rest of the app: a position is
 * written without asking the server where the file is.
 */
internal data class KavitaFileRef(
    val libraryId: Int,
    val seriesId: Int,
    val volumeId: Int,
    val chapterId: Int,
    val fileId: Int,
) {
    /** The app's id of the file. */
    val appId: FileId get() = FileId(listOf(libraryId, seriesId, volumeId, chapterId, fileId).joinToString(Separator))
}

/** The ids this file id carries; null for one that is not a Kavita file's. */
internal fun FileId.kavitaFile(): KavitaFileRef? {
    val numbers = value.split(Separator).map { it.kavitaNumber() ?: return null }
    if (numbers.size != Parts) return null
    val next = numbers.iterator()
    return KavitaFileRef(
        libraryId = next.next(),
        seriesId = next.next(),
        volumeId = next.next(),
        chapterId = next.next(),
        fileId = next.next(),
    )
}

/**
 * The app's name of the format of a Kavita file, from its format and extension: CBZ (a zip, or a folder of
 * images), CBR, CB7, EPUB, PDF. Null for one the app does not read.
 */
internal fun formatOf(file: FileDto): String? = when (file.format) {
    ImageFormat -> "CBZ"
    ArchiveFormat -> ArchiveExtensions[file.extension?.lowercase()]
    EpubFormat -> "EPUB"
    PdfFormat -> "PDF"
    else -> null
}

/**
 * The book's files: the first file of its [chapter], primary. Kavita's own readers read the first file of a
 * chapter, so a position means the same text in both. None when the app reads no format of it, or when the file
 * has no id. Its content version is its size, the one thing Kavita tells of a file that changes when it is
 * replaced.
 */
internal fun filesOf(chapter: ChapterDto, libraryId: Int, seriesId: Int): List<BookFile> {
    val file = chapter.files.firstOrNull()?.takeIf { it.id > 0 } ?: return emptyList()
    val format = formatOf(file) ?: return emptyList()
    val ref = KavitaFileRef(libraryId, seriesId, chapter.volumeId, chapter.id, file.id)
    val size = file.bytes.takeIf { it > 0 }
    return listOf(
        BookFile(
            id = ref.appId,
            kind = BookFileKind.Text,
            format = format,
            sizeBytes = size,
            isPrimary = true,
            narrationSeconds = null,
            chapterCount = null,
            contentVersion = size?.toString(),
        ),
    )
}

private const val Separator = "-"
private const val Parts = 5
private val ArchiveExtensions = mapOf(
    ".cbz" to "CBZ",
    ".zip" to "CBZ",
    ".cbr" to "CBR",
    ".rar" to "CBR",
    ".cb7" to "CB7",
    ".7z" to "CB7",
)
