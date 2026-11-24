package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.MediaFile
import app.tfdn.lirelle.core.library.model.isAudioFormat
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.network.fileIdOf
import kotlin.math.roundToLong

private const val PrimaryRole = "primary"
private val ContentRoles = setOf(PrimaryRole, "content")

/**
 * Whether a file with this BookOrbit role is something to read or listen to: a primary or content file is; a
 * cover image, a sidecar or a sample is not.
 */
internal fun isContentRole(role: String): Boolean = role in ContentRoles

/** Whether a file with this BookOrbit role is the one the server calls the book's own. */
internal fun isPrimaryRole(role: String): Boolean = role == PrimaryRole

internal fun mediaFile(dto: BookFileDto) = MediaFile(
    format = dto.format,
    isContent = isContentRole(dto.role),
    hasMediaOverlay = dto.mediaOverlay?.available == true,
)

internal fun bookFile(dto: BookFileDto, audioChapterCount: Int?): BookFile {
    val isAudio = isAudioFormat(dto.format)
    val isReadAlong = dto.format?.lowercase() == "epub" && dto.mediaOverlay?.available == true
    return BookFile(
        id = fileIdOf(dto.id),
        kind = when {
            isReadAlong -> BookFileKind.ReadAlongEpub
            isAudio -> BookFileKind.Audio
            else -> BookFileKind.Text
        },
        format = dto.format.orEmpty().uppercase(),
        sizeBytes = dto.sizeBytes,
        isPrimary = isPrimaryRole(dto.role),
        narrationSeconds = when {
            isReadAlong -> dto.mediaOverlay.durationSeconds
            isAudio -> dto.durationSeconds
            else -> null
        }?.roundToLong(),
        chapterCount = audioChapterCount.takeIf { isAudio },
        contentVersion = dto.contentVersion,
    )
}

/**
 * The book with the file its reader opens for an account that may not download. BookOrbit gives the text of a
 * read-along EPUB only to an account that may download: for another one, the file is the book's first plain EPUB,
 * in the server's order, when the usual file is a read-along one. A book that has no plain EPUB keeps its usual
 * file, which the server will refuse.
 */
internal fun BookDetail.withReaderFileWithoutDownload(): BookDetail {
    if (readerFile?.hasReadAlong != true) return this
    return copy(readerFileWithoutDownload = files.firstOrNull { it.isEpub && !it.hasReadAlong })
}
