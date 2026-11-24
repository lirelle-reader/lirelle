package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.store.InstantAsText
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.MediumCover
import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * A book's page as kept with its first download: all the page shows, and where the server said the user was.
 *
 * @property readingPercent null when the page never gave one.
 */
@Serializable
internal data class StoredPage(
    val detail: StoredDetail,
    val readingPercent: Int? = null,
    @Serializable(InstantAsText::class) val readAt: Instant? = null,
)

/**
 * [coverShape], [media], [readState] and [pageDirection] are the names of their values. [readerFileWithoutDownload]
 * is the id of one of [files]. A page kept before [readerFileWithoutDownload] or [pageDirection] existed has none.
 */
@Serializable
internal data class StoredDetail(
    val id: BookId,
    val title: String,
    val subtitle: String? = null,
    val authors: List<StoredAuthor> = emptyList(),
    val series: StoredSeries? = null,
    val coverUrl: String? = null,
    val coverShape: String,
    val media: String,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Float? = null,
    val pageCount: Int? = null,
    val publishedYear: Int? = null,
    val publisher: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val libraryName: String,
    @Serializable(InstantAsText::class) val addedAt: Instant? = null,
    val narrators: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val files: List<StoredBookFile> = emptyList(),
    val readState: String? = null,
    @Serializable(InstantAsText::class) val startedAt: Instant? = null,
    val audioCoverUrl: String? = null,
    val audioCoverThumbnailUrl: String? = null,
    val readerFileWithoutDownload: FileId? = null,
    val pageDirection: String? = null,
)

/** The author's id is kept as its text, as every id the app names things by. */
@Serializable
internal data class StoredAuthor(val id: AuthorId, val name: String)

/** The series's id, when it has one, is kept as its text. */
@Serializable
internal data class StoredSeries(val id: SeriesId? = null, val name: String, val index: String? = null)

/** [kind] is the name of a [BookFileKind]; the file's id is kept as its text. */
@Serializable
internal data class StoredBookFile(
    val id: FileId,
    val kind: String,
    val format: String,
    val sizeBytes: Long? = null,
    val isPrimary: Boolean = false,
    val narrationSeconds: Long? = null,
    val chapterCount: Int? = null,
    val contentVersion: String? = null,
)

internal fun BookDetail.stored() = StoredDetail(
    id = id,
    title = title,
    subtitle = subtitle,
    authors = authors.map { StoredAuthor(it.id, it.name) },
    series = series?.let { StoredSeries(it.id, it.name, it.index) },
    coverUrl = coverUrl,
    coverShape = coverShape.name,
    media = media.name,
    description = description,
    genres = genres,
    rating = rating,
    pageCount = pageCount,
    publishedYear = publishedYear,
    publisher = publisher,
    language = language,
    isbn = isbn,
    libraryName = libraryName,
    addedAt = addedAt,
    narrators = narrators,
    collections = collections,
    files = files.map { it.stored() },
    readState = readState?.name,
    startedAt = startedAt,
    audioCoverUrl = audioCover?.url,
    audioCoverThumbnailUrl = audioCover?.thumbnailUrl,
    readerFileWithoutDownload = readerFileWithoutDownload?.id,
    pageDirection = pageDirection?.name,
)

/**
 * A value this version does not know reads as the plainest one: a portrait cover, a text, no reading state, no
 * direction. A file of a kind it does not know is left out, and so is the reader file named by its id when it is
 * not one of the files read.
 */
internal fun StoredDetail.detail() = BookDetail(
    id = id,
    title = title,
    subtitle = subtitle,
    authors = authors.map { AuthorRef(it.id, it.name) },
    series = series?.let { SeriesRef(it.id, it.name, it.index) },
    coverUrl = coverUrl,
    coverShape = CoverShape.entries.firstOrNull { it.name == coverShape } ?: CoverShape.Portrait,
    media = MediaProfile.entries.firstOrNull { it.name == media } ?: MediaProfile.TextOnly,
    description = description,
    genres = genres,
    rating = rating,
    pageCount = pageCount,
    publishedYear = publishedYear,
    publisher = publisher,
    language = language,
    isbn = isbn,
    libraryName = libraryName,
    addedAt = addedAt,
    narrators = narrators,
    collections = collections,
    files = files.mapNotNull { it.file() },
    readState = ReadState.entries.firstOrNull { it.name == readState },
    startedAt = startedAt,
    audioCover = audioCoverUrl?.let { url -> audioCoverThumbnailUrl?.let { MediumCover(url, it) } },
    pageDirection = PageDirection.entries.firstOrNull { it.name == pageDirection },
).let { read -> read.copy(readerFileWithoutDownload = read.files.firstOrNull { it.id == readerFileWithoutDownload }) }

private fun BookFile.stored() =
    StoredBookFile(id, kind.name, format, sizeBytes, isPrimary, narrationSeconds, chapterCount, contentVersion)

private fun StoredBookFile.file(): BookFile? = BookFileKind.entries.firstOrNull { it.name == kind }?.let {
    BookFile(id, it, format, sizeBytes, isPrimary, narrationSeconds, chapterCount, contentVersion)
}
