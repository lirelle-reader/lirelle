package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesRef
import java.time.Instant

/** A book for tests, named by [id]'s text, as any source's id is. Covers are null so no image is ever requested. */
@Suppress("LongParameterList") // Every parameter but the id has a default; tests name the ones they set.
fun sampleBook(
    id: String,
    title: String = "Book $id",
    author: String = "Author $id",
    shape: CoverShape = CoverShape.Portrait,
    media: MediaProfile = MediaProfile.TextOnly,
    series: SeriesRef? = null,
    readState: ReadState? = null,
    progressPercent: Int? = null,
    pagedKind: PagedKind? = null,
) = BookSummary(
    id = BookId(id),
    title = title,
    authors = listOf(author),
    series = series,
    coverUrl = null,
    coverShape = shape,
    media = media,
    readState = readState,
    progressPercent = progressPercent,
    pagedKind = pagedKind,
)

/** A series for tests, named by [id]'s text, as any source's id is. */
fun sampleSeries(id: String, name: String = "Series $id", books: Int = 3, read: Int = 1) = Series(
    id = SeriesId(id),
    name = name,
    authors = listOf("Author $id"),
    bookCount = books,
    readCount = read,
    coverUrls = emptyList(),
    lastAddedAt = null,
)

/** A complete book page: every block has data. */
fun sampleBookDetail(
    id: String = "12",
    media: MediaProfile = MediaProfile.ReadAlong,
    series: SeriesRef? = SeriesRef(id = SeriesId("4"), name = "A Saga", index = "2"),
) = BookDetail(
    id = BookId(id),
    title = "A Complete Book",
    subtitle = "With a subtitle",
    authors = listOf(AuthorRef(id = AuthorId("9"), name = "Ursula Writer")),
    series = series,
    coverUrl = null,
    coverShape = CoverShape.Portrait,
    media = media,
    description = "<p>The summary of the book.</p>",
    genres = listOf("Fantasy", "Adventure"),
    rating = 4.5f,
    pageCount = 412,
    publishedYear = 2019,
    publisher = "A Publisher",
    language = "en",
    isbn = "9780000000002",
    libraryName = "Main library",
    addedAt = Instant.parse("2026-01-15T10:00:00Z"),
    narrators = listOf("A Narrator"),
    collections = listOf("Favourites"),
    files = listOf(
        BookFile(
            id = FileId("1"),
            kind = BookFileKind.ReadAlongEpub,
            format = "EPUB",
            sizeBytes = 48_000_000,
            isPrimary = true,
            narrationSeconds = 36_000,
            chapterCount = null,
        ),
        BookFile(
            id = FileId("2"),
            kind = BookFileKind.Audio,
            format = "M4B",
            sizeBytes = 310_000_000,
            isPrimary = false,
            narrationSeconds = 36_500,
            chapterCount = 24,
        ),
    ),
    readState = ReadState.Reading,
    startedAt = Instant.parse("2026-02-01T08:00:00Z"),
)

/** A comic: the book page of an album whose only file is a CBZ of id 31. */
fun sampleComicDetail(
    id: String = "14",
    series: SeriesRef? = SeriesRef(id = SeriesId("4"), name = "A Saga", index = "2"),
) = sampleBookDetail(id = id, media = MediaProfile.TextOnly, series = series).copy(
    title = "A Complete Album",
    narrators = emptyList(),
    files = listOf(
        BookFile(
            id = FileId("31"),
            kind = BookFileKind.Text,
            format = "CBZ",
            sizeBytes = 96_000_000,
            isPrimary = true,
            narrationSeconds = null,
            chapterCount = null,
        ),
    ),
)

/** A book read by pages that is not a comic: the same page, whose only file, of id 32, is of [format] (PDF, DJVU). */
fun samplePagedDetail(
    format: String,
    id: String = "14",
    series: SeriesRef? = SeriesRef(id = SeriesId("4"), name = "A Saga", index = "2"),
): BookDetail {
    val comic = sampleComicDetail(id, series)
    val file = comic.files.single().copy(id = FileId("32"), format = format, sizeBytes = 9_400_000)
    return comic.copy(title = "A Complete Document", files = listOf(file))
}
