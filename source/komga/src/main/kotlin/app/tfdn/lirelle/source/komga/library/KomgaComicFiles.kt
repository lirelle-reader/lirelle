package app.tfdn.lirelle.source.komga.library

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.source.komga.network.dto.BookDto

/** The media profile of a book Komga reads as pictures (Readium's name for them). */
private const val DivinaProfile = "DIVINA"

/** The status of a book's media Komga serves the pages of. */
private const val ReadyStatus = "READY"

/**
 * Whether Komga reads the book's file as an album of pictures, whatever the state of its media: its card opens the
 * comics reader, which reads the copy the device keeps while Komga serves no page of it, or says there is no comic.
 */
internal fun readsAsPictures(dto: BookDto): Boolean = dto.media.mediaProfile == DivinaProfile

/**
 * Whether Komga reads the book's file as an album of pictures and serves its pages: a comic with a file the comics
 * reader opens. A book Komga has not analysed, or whose file changed since (its media not ready), has none yet.
 */
internal fun isComic(dto: BookDto): Boolean = readsAsPictures(dto) && dto.media.status == ReadyStatus

/**
 * Whether Komga holds the book's file but serves none of it for now: its media is not ready, as Komga has not
 * analysed the file yet, analyses it again since it changed, or could not analyse it.
 */
internal fun filesUnavailable(dto: BookDto): Boolean = dto.media.status != ReadyStatus

/**
 * The book's one file when it is a comic, which the comics reader opens by its pages: its id is the book's, its
 * format named by its archive (CBZ when the app has no name for it), its version the date of its file on the
 * server's disk. Null for a PDF or an EPUB of Komga's, not read yet, and for a book Komga has not analysed, or
 * whose file changed since (its media not ready: Komga serves none of its pages).
 */
internal fun comicFileOf(dto: BookDto): BookFile? {
    if (!isComic(dto)) return null
    return BookFile(
        id = FileId(dto.id),
        kind = BookFileKind.Text,
        format = archiveFormatOf(dto.media.mediaType),
        sizeBytes = dto.sizeBytes.takeIf { it > 0 },
        isPrimary = true,
        narrationSeconds = null,
        chapterCount = null,
        contentVersion = dto.fileLastModified?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** The comic format of an archive of this media type (`application/x-rar-compressed; version=5` is a CBR). */
private fun archiveFormatOf(mediaType: String): String {
    val type = mediaType.substringBefore(';').trim().lowercase()
    return when {
        "rar" in type -> "CBR"
        "7z" in type -> "CB7"
        else -> "CBZ"
    }
}
