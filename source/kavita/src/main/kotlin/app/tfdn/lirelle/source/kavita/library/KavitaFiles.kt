package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.FileDto

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
 * chapter, so a position means the same text in both. None when the app reads no format of it, or when an id
 * is missing (a 0 would make an id [kavitaFile] refuses). Its content version is its id and its size: a file
 * replaced under another id, or with another size, is seen as changed. A chapter of several files served whole
 * (EPUB, PDF) cannot be kept: Kavita sends it as a zip of them.
 */
internal fun filesOf(chapter: ChapterDto, libraryId: Int, seriesId: Int): List<BookFile> {
    val file = chapter.files.firstOrNull() ?: return emptyList()
    if (!listOf(libraryId, seriesId, chapter.volumeId, chapter.id, file.id).all { it > 0 }) return emptyList()
    val format = formatOf(file) ?: return emptyList()
    // Kavita sends a chapter of several files as a zip of them: kept as it comes, it would be no EPUB nor PDF.
    // A comic is kept as its pages, whatever its archives.
    val keepable = chapter.files.size == 1 || pagedKindOf(file.format) == PagedKind.Comic
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
            contentVersion = size?.let { "${file.id}$VersionSeparator$it" },
            keepable = keepable,
        ),
    )
}

private const val VersionSeparator = ":"

private val ArchiveExtensions = mapOf(
    ".cbz" to "CBZ",
    ".zip" to "CBZ",
    ".cbr" to "CBR",
    ".rar" to "CBR",
    ".cb7" to "CB7",
    ".7z" to "CB7",
)
