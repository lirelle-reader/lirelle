package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.source.bookorbit.library.dto.AuthorSummaryDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookCardDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BooksPageDto
import app.tfdn.lirelle.source.bookorbit.library.dto.FileProgressDto
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadStatusDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadingProgressListDto
import app.tfdn.lirelle.source.bookorbit.library.dto.RelatedBookDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesInfoDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesPageDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesSummaryDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SessionStatsDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SessionsDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SmartScopeDto
import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class MappersTest {

    private val mapper = LibraryMapper("https://books.example.com")

    private fun card(
        files: List<BookFileDto> = listOf(BookFileDto(1, "epub", "primary")),
        hasCover: Boolean = true,
        status: String? = null,
        progress: Double? = null,
        ratio: String = "2/3",
    ) = BookCardDto(
        id = 12,
        title = "A title",
        authors = listOf("An Author"),
        seriesId = 4,
        seriesName = "A series",
        seriesIndex = "2",
        files = files,
        coverAspectRatio = ratio,
        hasCover = hasCover,
        coverVersion = "ebook:2026-01-02T03:04:05.000Z:-",
        readingProgress = progress,
        readStatus = status?.let { ReadStatusDto(it) },
    )

    @Test
    fun `a card becomes a summary`() {
        val book = mapper.summary(card(status = "reading", progress = 41.6))

        assertThat(book.id).isEqualTo(BookId("12"))
        assertThat(book.title).isEqualTo("A title")
        assertThat(book.authors).containsExactly("An Author")
        assertThat(book.series).isEqualTo(SeriesRef(SeriesId("4"), "A series", "2"))
        assertThat(book.coverShape).isEqualTo(CoverShape.Portrait)
        assertThat(book.media).isEqualTo(MediaProfile.TextOnly)
        assertThat(book.readState).isEqualTo(ReadState.Reading)
        assertThat(book.progressPercent).isEqualTo(42)
        assertThat(book.isInProgress).isTrue()
    }

    @Test
    fun `the thumbnail url carries the cover version, encoded`() {
        assertThat(mapper.summary(card()).coverUrl).isEqualTo(
            "https://books.example.com/api/v1/books/12/thumbnail?t=ebook%3A2026-01-02T03%3A04%3A05.000Z%3A-",
        )
    }

    @Test
    fun `a book without a cover has no cover url`() {
        assertThat(mapper.summary(card(hasCover = false)).coverUrl).isNull()
    }

    @Test
    fun `a missing title is an empty string and no series is null`() {
        val book = mapper.summary(BookCardDto(id = 3))

        assertThat(book.title).isEmpty()
        assertThat(book.series).isNull()
        assertThat(book.readState).isNull()
        assertThat(book.progressPercent).isNull()
        assertThat(book.isInProgress).isFalse()
    }

    @Test
    fun `square covers and media profiles are derived`() {
        val audio = mapper.summary(card(files = listOf(BookFileDto(1, "m4b", "primary")), ratio = "1/1"))
        assertThat(audio.coverShape).isEqualTo(CoverShape.Square)
        assertThat(audio.media).isEqualTo(MediaProfile.AudioOnly)

        val readAlong = mapper.summary(
            card(files = listOf(BookFileDto(1, "epub", "primary", mediaOverlay = MediaOverlayDto(available = true)))),
        )
        assertThat(readAlong.media).isEqualTo(MediaProfile.ReadAlong)
    }

    @Test
    fun `only primary and content files are content, in a card as on a book's page`() {
        val files = listOf(
            BookFileDto(1, "epub", "primary"),
            BookFileDto(2, "mp3", "sample"),
            BookFileDto(3, "epub", "sidecar", mediaOverlay = MediaOverlayDto(available = true)),
        )
        val summary = mapper.summary(card(files = files))
        val detail = mapper.detail(BookDetailDto(id = 12, addedAt = "2026-11-08T10:00:00.000Z", files = files), null)

        assertThat(summary.media).isEqualTo(MediaProfile.TextOnly)
        assertThat(summary.hasAudioFiles).isFalse()
        assertThat(detail.media).isEqualTo(MediaProfile.TextOnly)
        assertThat(mapper.summary(card(files = files + BookFileDto(4, "m4b", "content"))).media)
            .isEqualTo(MediaProfile.TextAndAudio)
    }

    @Test
    fun `the eight server statuses map to five`() {
        fun state(status: String) = mapper.summary(card(status = status)).readState

        assertThat(state("unread")).isEqualTo(ReadState.Unread)
        assertThat(state("want_to_read")).isEqualTo(ReadState.WantToRead)
        assertThat(state("reading")).isEqualTo(ReadState.Reading)
        assertThat(state("rereading")).isEqualTo(ReadState.Reading)
        assertThat(state("on_hold")).isEqualTo(ReadState.Reading)
        assertThat(state("read")).isEqualTo(ReadState.Read)
        assertThat(state("skimmed")).isEqualTo(ReadState.Read)
        assertThat(state("abandoned")).isEqualTo(ReadState.Abandoned)
        assertThat(state("something-new")).isNull()
    }

    @Test
    fun `progress is rounded and kept in range`() {
        assertThat(mapper.summary(card(progress = 0.0)).progressPercent).isEqualTo(0)
        assertThat(mapper.summary(card(progress = 99.5)).progressPercent).isEqualTo(100)
        assertThat(mapper.summary(card(progress = 140.0)).progressPercent).isEqualTo(100)
        assertThat(mapper.summary(card(progress = 100.0)).isInProgress).isFalse()
    }

    @Test
    fun `a series takes at most three covers`() {
        val series = mapper.series(
            SeriesSummaryDto(
                id = 9,
                name = "S",
                bookCount = 5,
                readCount = 2,
                authors = listOf("A"),
                coverBookIds = listOf(1, 2, 3, 4),
                lastAddedAt = "2026-05-06T07:08:09.000Z",
            ),
        )

        assertThat(series.coverUrls).containsExactly(
            "https://books.example.com/api/v1/books/1/thumbnail",
            "https://books.example.com/api/v1/books/2/thumbnail",
            "https://books.example.com/api/v1/books/3/thumbnail",
        ).inOrder()
        assertThat(series.lastAddedAt).isEqualTo(Instant.parse("2026-05-06T07:08:09Z"))
        assertThat(series.unreadCount).isEqualTo(3)
    }

    @Test
    fun `a series date in the database's own format is read`() {
        fun added(text: String) = mapper.series(SeriesSummaryDto(id = 9, name = "S", lastAddedAt = text)).lastAddedAt

        assertThat(added("2017-08-20 10:05:17.785287+00")).isEqualTo(Instant.parse("2017-08-20T10:05:17.785287Z"))
        assertThat(added("2017-08-20 10:05:17+00")).isEqualTo(Instant.parse("2017-08-20T10:05:17Z"))
        assertThat(added("2017-08-20 10:05:17+05:30")).isEqualTo(Instant.parse("2017-08-20T04:35:17Z"))
        assertThat(added("not a date")).isNull()
    }

    @Test
    fun `a series date is read whatever the form of its offset`() {
        fun added(text: String) = mapper.series(SeriesSummaryDto(id = 9, name = "S", lastAddedAt = text)).lastAddedAt

        assertThat(added("2017-08-20 10:05:17.785287+02")).isEqualTo(Instant.parse("2017-08-20T08:05:17.785287Z"))
        assertThat(added("2017-08-20 10:05:17+02:00")).isEqualTo(Instant.parse("2017-08-20T08:05:17Z"))
        assertThat(added("2017-08-20 10:05:17+0200")).isEqualTo(Instant.parse("2017-08-20T08:05:17Z"))
        assertThat(added("2017-08-20 10:05:17-0330")).isEqualTo(Instant.parse("2017-08-20T13:35:17Z"))
        assertThat(added("2017-08-20 10:05:17-03")).isEqualTo(Instant.parse("2017-08-20T13:05:17Z"))
        assertThat(added("2017-08-20 10:05:17+00:00")).isEqualTo(Instant.parse("2017-08-20T10:05:17Z"))
        assertThat(added("2017-08-20 10:05:17+0000")).isEqualTo(Instant.parse("2017-08-20T10:05:17Z"))
    }

    @Test
    fun `every recorded series has a date`() {
        val page = ApiJson.decodeFromString<SeriesPageDto>(Fixtures.recorded("series-page"))

        assertThat(page.items).isNotEmpty()
        page.items.forEach { assertThat(mapper.series(it).lastAddedAt).isNotNull() }
    }

    @Test
    fun `an author portrait url is made absolute`() {
        val withPortrait = mapper.author(AuthorSummaryDto(5, "Name", 3, "/api/v1/authors/5/thumbnail?t=17"))
        assertThat(withPortrait.portraitUrl).isEqualTo("https://books.example.com/api/v1/authors/5/thumbnail?t=17")

        assertThat(mapper.author(AuthorSummaryDto(6, "Other")).portraitUrl).isNull()
    }

    @Test
    fun `an author counts works where the server counts them, books otherwise`() {
        assertThat(mapper.author(AuthorSummaryDto(53, "Name", bookCount = 23, workCount = 7)).bookCount).isEqualTo(7)
        assertThat(mapper.author(AuthorSummaryDto(53, "Name", bookCount = 23)).bookCount).isEqualTo(23)
    }

    @Test
    fun `a series counts its volumes, an edition of a volume not counted twice`() {
        val listed = mapper.series(
            SeriesSummaryDto(
                id = 9,
                name = "S",
                bookCount = 6,
                readCount = 4,
                ownedVolumeCount = 5,
                readVolumeCount = 3,
            ),
        )
        val opened = mapper.series(
            SeriesInfoDto(id = 9, name = "S", bookCount = 6, readCount = 4, ownedVolumeCount = 5, readVolumeCount = 3),
            coverUrls = emptyList(),
        )

        assertThat(listed.bookCount).isEqualTo(5)
        assertThat(listed.readCount).isEqualTo(3)
        assertThat(opened.bookCount).isEqualTo(5)
        assertThat(opened.readCount).isEqualTo(3)
    }

    @Test
    fun `a series whose server counts no volumes counts its books`() {
        val listed = mapper.series(SeriesSummaryDto(id = 9, name = "S", bookCount = 6, readCount = 4))
        val opened = mapper.series(
            SeriesInfoDto(id = 9, name = "S", bookCount = 6, readCount = 4),
            coverUrls = emptyList(),
        )

        assertThat(listed.bookCount).isEqualTo(6)
        assertThat(listed.readCount).isEqualTo(4)
        assertThat(opened.bookCount).isEqualTo(6)
        assertThat(opened.readCount).isEqualTo(4)
    }

    @Test
    fun `a series with one volume count only counts its books, never one of each`() {
        val listed = mapper.series(
            SeriesSummaryDto(id = 9, name = "S", bookCount = 6, readCount = 6, ownedVolumeCount = 5),
        )
        val opened = mapper.series(
            SeriesInfoDto(id = 9, name = "S", bookCount = 6, readCount = 2, readVolumeCount = 1),
            coverUrls = emptyList(),
        )

        assertThat(listed.bookCount).isEqualTo(6)
        assertThat(listed.readCount).isEqualTo(6)
        assertThat(opened.bookCount).isEqualTo(6)
        assertThat(opened.readCount).isEqualTo(2)
    }

    @Test
    fun `a smart list keeps an unknown count`() {
        val shelf = mapper.smartList(SmartScopeDto(3, "To read", bookCount = null))

        assertThat(shelf.kind).isEqualTo(ShelfKind.SmartList)
        assertThat(shelf.bookCount).isNull()
    }

    @Test
    fun `related books`() {
        val related = mapper.related(RelatedBookDto(8, "T", listOf("A"), "1/1", hasCover = true, coverVersion = "v 1"))

        assertThat(related.coverUrl).isEqualTo("https://books.example.com/api/v1/books/8/thumbnail?t=v+1")
        assertThat(related.coverShape).isEqualTo(CoverShape.Square)
        assertThat(mapper.related(RelatedBookDto(9, hasCover = true, coverVersion = null)).coverUrl)
            .isEqualTo("https://books.example.com/api/v1/books/9/thumbnail")
    }

    private fun recordedRelated(name: String) =
        ApiJson.decodeFromString<List<RelatedBookDto>>(Fixtures.recorded(name)).map(mapper::related)

    @Test
    fun `the books of the series of a book carry their volume numbers`() {
        val books = recordedRelated("book-series-books")

        assertThat(books.take(4).map { it.seriesIndex }).containsExactly("1", "2", "3", "5").inOrder()
        assertThat(books.map { it.seriesIndex }).doesNotContain(null)
        // Nothing of this series was read when it was recorded.
        assertThat(books.mapNotNull { it.readState }).isEmpty()
    }

    @Test
    fun `related books carry their read state`() {
        val sameAuthor = recordedRelated("book-author-books")
        val similar = recordedRelated("book-recommendations")

        assertThat(sameAuthor.groupingBy { it.readState }.eachCount()).containsExactly(
            ReadState.Read, 4,
            ReadState.Reading, 1,
            ReadState.Unread, 1,
            null, 12,
        )
        assertThat(sameAuthor.count { it.isRead }).isEqualTo(4)
        assertThat(similar.count { it.isRead }).isEqualTo(5)
        assertThat(similar.count { it.readState == null }).isEqualTo(20)
    }

    @Test
    fun `a related book without a volume number or a status has neither`() {
        val related = mapper.related(RelatedBookDto(8, "T"))

        assertThat(related.seriesIndex).isNull()
        assertThat(related.readState).isNull()
        assertThat(related.isRead).isFalse()
    }

    @Test
    fun `a read book is not in progress, whatever is left of its progress`() {
        val read = mapper.summary(card(status = "read", progress = 60.0))

        assertThat(read.isRead).isTrue()
        assertThat(read.isInProgress).isFalse()
        assertThat(mapper.summary(card(status = "reading", progress = 60.0)).isRead).isFalse()
    }

    @Test
    fun `book detail from a hand-written response`() {
        val dto = BookDetailDto(
            id = 20,
            libraryName = "EBooks",
            addedAt = "2026-11-08T10:00:00.000Z",
            title = "T",
            isbn13 = "9780000000002",
            isbn10 = "0000000000",
            rating = 4.0,
            authors = emptyList(),
            files = listOf(
                BookFileDto(1, "epub", "primary", 1_080_000_000, mediaOverlay = MediaOverlayDto(true, 129_960.4)),
                BookFileDto(2, "epub", "content", 2_800_000),
                BookFileDto(3, "m4b", "content", 1_070_000_000, durationSeconds = 129_960.0),
            ),
            coverMedia = listOf("ebook"),
            coverVersion = "v1",
            readStatus = ReadStatusDto("reading", startedAt = "2026-11-10T08:00:00.000Z"),
        )

        val book = mapper.detail(dto, audioChapterCount = 139)

        assertThat(book.media).isEqualTo(MediaProfile.ReadAlong)
        assertThat(book.coverUrl).isEqualTo("https://books.example.com/api/v1/books/20/cover?t=v1")
        assertThat(book.isbn).isEqualTo("9780000000002")
        assertThat(book.rating).isEqualTo(4f)
        assertThat(book.addedAt).isEqualTo(Instant.parse("2026-11-08T10:00:00Z"))
        assertThat(book.startedAt).isEqualTo(Instant.parse("2026-11-10T08:00:00Z"))
        assertThat(book.files.map { it.kind })
            .containsExactly(BookFileKind.ReadAlongEpub, BookFileKind.Text, BookFileKind.Audio).inOrder()
        assertThat(book.files[0].isPrimary).isTrue()
        assertThat(book.files[0].format).isEqualTo("EPUB")
        assertThat(book.files[0].narrationSeconds).isEqualTo(129_960)
        assertThat(book.files[1].narrationSeconds).isNull()
        assertThat(book.files[2].narrationSeconds).isEqualTo(129_960)
        assertThat(book.files[2].chapterCount).isEqualTo(139)
        assertThat(book.files[0].chapterCount).isNull()
    }

    @Test
    fun `the cover image of a recorded book is not one of its files`() {
        val dto = ApiJson.decodeFromString<BookDetailDto>(Fixtures.recorded("book-in-series"))
        assertThat(dto.files.map { it.role }).containsExactly("primary", "cover")

        val book = mapper.detail(dto, audioChapterCount = null)

        assertThat(book.files.map { it.format }).containsExactly("EPUB")
        assertThat(book.files.single().isPrimary).isTrue()
    }

    @Test
    fun `only primary and content files are files of the book`() {
        val dto = BookDetailDto(
            id = 22,
            addedAt = "2026-11-08T10:00:00.000Z",
            files = listOf(
                BookFileDto(1, "jpg", "cover"),
                BookFileDto(2, "m4b", "content"),
                BookFileDto(3, "epub", "primary"),
                BookFileDto(4, "opf", "metadata"),
            ),
        )

        assertThat(mapper.detail(dto, audioChapterCount = null).files.map { it.id })
            .containsExactly(FileId("2"), FileId("3")).inOrder()
    }

    @Test
    fun `book detail leaves out genres without a name`() {
        val dto = BookDetailDto(id = 22, addedAt = "not a date", genres = listOf("Fantasy", "", "  ", "SF"))

        assertThat(mapper.detail(dto, audioChapterCount = null).genres).containsExactly("Fantasy", "SF").inOrder()
    }

    @Test
    fun `book detail without any cover medium has no cover url`() {
        val dto = BookDetailDto(id = 21, addedAt = "not a date")

        val book = mapper.detail(dto, audioChapterCount = null)

        assertThat(book.coverUrl).isNull()
        assertThat(book.addedAt).isNull()
        assertThat(book.title).isEmpty()
    }

    @Test
    fun `reading combines progress and session statistics`() {
        val reading = mapper.reading(
            ReadingProgressListDto(
                listOf(
                    FileProgressDto(1, 11.2, "2026-11-12T21:30:00.000Z"),
                    FileProgressDto(2, null),
                    FileProgressDto(3, 4.0, "2026-11-02T10:00:00.000Z"),
                ),
            ),
            SessionStatsDto(totalSessions = 29, totalSeconds = 15_060, lastSessionAt = "2026-11-13T07:00:00.000Z"),
        )

        assertThat(reading.progressPercent).isEqualTo(11)
        assertThat(reading.sessionCount).isEqualTo(29)
        assertThat(reading.totalSeconds).isEqualTo(15_060)
        assertThat(reading.lastReadAt).isEqualTo(Instant.parse("2026-11-13T07:00:00Z"))
        assertThat(reading.hasAny).isTrue()
    }

    @Test
    fun `a card whose file to read is a comic is a comic`() {
        fun isComic(vararg files: BookFileDto) =
            mapper.summary(card(files = files.toList())).pagedKind == PagedKind.Comic

        assertThat(isComic(BookFileDto(1, "cbz", "primary"))).isTrue()
        assertThat(isComic(BookFileDto(1, "txt", "primary"), BookFileDto(2, "cbr", "content"))).isTrue()
        assertThat(isComic(BookFileDto(1, "m4b", "primary"), BookFileDto(2, "cbz", "content"))).isTrue()
        assertThat(isComic(BookFileDto(1, "cb7", "content"))).isTrue()
        assertThat(isComic(BookFileDto(1, "epub", "content"), BookFileDto(2, "cbz", "primary"))).isTrue()
        assertThat(isComic(BookFileDto(1, "cbz", "content"), BookFileDto(2, "cbz", "primary"))).isTrue()
        assertThat(isComic(BookFileDto(1, "cbz", "content"), BookFileDto(2, "epub", "primary"))).isFalse()
        assertThat(isComic(BookFileDto(1, "cbz", "content"), BookFileDto(2, "kepub", "primary"))).isFalse()
        assertThat(isComic(BookFileDto(1, "cbz", "content"), BookFileDto(2, "epub", "content"))).isFalse()
        val epubBeside = BookFileDto(3, "epub", "content")
        assertThat(isComic(BookFileDto(1, "m4b", "primary"), BookFileDto(2, "cbz", "content"), epubBeside)).isFalse()
        assertThat(isComic(BookFileDto(1, "epub", "primary"))).isFalse()
        assertThat(isComic(BookFileDto(1, "m4b", "primary"))).isFalse()
        assertThat(isComic(BookFileDto(1, "cbz", "sidecar"))).isFalse()
        assertThat(isComic()).isFalse()
    }

    @Test
    fun `a card says by which kind of pages its file to read is read, as the book's page does`() {
        fun kind(vararg files: BookFileDto) = mapper.summary(card(files = files.toList())).pagedKind
        val mobi = BookFileDto(1, "mobi", "content")
        val pdf = BookFileDto(2, "pdf", "content")
        val audio = BookFileDto(3, "m4b", "primary")

        assertThat(kind(BookFileDto(1, "pdf", "primary"))).isEqualTo(PagedKind.Pdf)
        assertThat(kind(BookFileDto(1, "djvu", "content"))).isEqualTo(PagedKind.Djvu)
        assertThat(kind(BookFileDto(1, "cbr", "content"))).isEqualTo(PagedKind.Comic)
        assertThat(kind(BookFileDto(1, "epub", "content"), BookFileDto(2, "pdf", "primary"))).isEqualTo(PagedKind.Pdf)
        assertThat(kind(mobi, pdf, BookFileDto(3, "epub", "primary"))).isNull()
        assertThat(kind(BookFileDto(1, "mobi", "primary"))).isNull()
        assertThat(kind(audio, pdf, mobi)).isNull()
        assertThat(kind(audio, BookFileDto(1, "cbz", "content"), pdf)).isEqualTo(PagedKind.Pdf)
        assertThat(kind(BookFileDto(1, "kepub", "content"), BookFileDto(2, "djvu", "content"))).isNull()
        assertThat(kind(BookFileDto(1, "pdf", "sidecar"))).isNull()
        assertThat(kind(audio)).isNull()
        assertThat(kind()).isNull()
    }

    @Test
    fun `a card's book is paged when its file to read is read by pages`() {
        assertThat(mapper.summary(card(files = listOf(BookFileDto(1, "pdf", "primary")))).isPaged).isTrue()
        assertThat(mapper.summary(card(files = listOf(BookFileDto(1, "mobi", "primary")))).isPaged).isFalse()
    }

    @Test
    fun `the reading of a comic carries the page of the file read most recently`() {
        val progress = ApiJson.decodeFromString<ReadingProgressListDto>(
            Fixtures.read("handmade/book-reading-progress-comic.json"),
        )

        val reading = mapper.reading(progress, SessionStatsDto())

        assertThat(reading.progressPercent).isEqualTo(25)
        assertThat(reading.page).isEqualTo(12)
    }

    @Test
    fun `the reading of a book has no page`() {
        val progress = ReadingProgressListDto(listOf(FileProgressDto(1, 20.0, "2026-11-12T21:30:00.000Z")))

        assertThat(mapper.reading(progress, SessionStatsDto()).page).isNull()
    }

    @Test
    fun `a book never opened has no reading`() {
        val reading = mapper.reading(ReadingProgressListDto(), SessionStatsDto())

        assertThat(reading.progressPercent).isNull()
        assertThat(reading.hasAny).isFalse()
    }

    @Test
    fun `a recorded book never opened has no reading, whatever its files report`() {
        val progress = ApiJson.decodeFromString<ReadingProgressListDto>(Fixtures.recorded("book-reading-progress"))
        val sessions = ApiJson.decodeFromString<SessionsDto>(Fixtures.recorded("book-sessions"))

        val reading = mapper.reading(progress, sessions.stats)

        assertThat(progress.items).isNotEmpty()
        assertThat(reading.progressPercent).isNull()
        assertThat(reading.lastReadAt).isNull()
        assertThat(reading.hasAny).isFalse()
    }

    @Test
    fun `reading is that of the file read most recently, files never read left out`() {
        val reading = mapper.reading(
            ReadingProgressListDto(
                listOf(
                    FileProgressDto(1, 64.0, "2026-11-01T08:00:00.000Z"),
                    FileProgressDto(2, 11.146635, "2026-11-12T21:30:00.000Z"),
                    FileProgressDto(3, 0.0, null),
                    FileProgressDto(4, 97.0, null),
                ),
            ),
            SessionStatsDto(),
        )

        assertThat(reading.progressPercent).isEqualTo(11)
        assertThat(reading.lastReadAt).isEqualTo(Instant.parse("2026-11-12T21:30:00Z"))
        assertThat(reading.hasAny).isTrue()
    }

    @Test
    fun `the last reading is the later of the progress and the last session`() {
        val progress = ReadingProgressListDto(listOf(FileProgressDto(1, 20.0, "2026-11-12T21:30:00.000Z")))

        val sessionLater = mapper.reading(progress, SessionStatsDto(1, 60, lastSessionAt = "2026-11-13T07:00:00.000Z"))
        val progressLater = mapper.reading(progress, SessionStatsDto(1, 60, lastSessionAt = "2026-11-10T07:00:00.000Z"))

        assertThat(sessionLater.lastReadAt).isEqualTo(Instant.parse("2026-11-13T07:00:00Z"))
        assertThat(progressLater.lastReadAt).isEqualTo(Instant.parse("2026-11-12T21:30:00Z"))
    }

    @Test
    fun `the reading position is dated by the latest dated progress item, not by the sessions`() {
        val progress = ReadingProgressListDto(
            listOf(
                FileProgressDto(1, 64.0, "2026-11-01T08:00:00.000Z"),
                FileProgressDto(2, 20.0, "2026-11-12T21:30:00.000Z"),
                FileProgressDto(3, 0.0, null),
            ),
        )

        val reading = mapper.reading(progress, SessionStatsDto(1, 60, lastSessionAt = "2026-11-13T07:00:00.000Z"))

        assertThat(reading.positionAt).isEqualTo(Instant.parse("2026-11-12T21:30:00Z"))
        assertThat(reading.lastReadAt).isEqualTo(Instant.parse("2026-11-13T07:00:00Z"))
    }

    @Test
    fun `a reading with no dated progress item has no position date, whatever its sessions`() {
        val progress = ReadingProgressListDto(listOf(FileProgressDto(1, 0.0, null), FileProgressDto(2, 97.0, null)))

        val reading = mapper.reading(progress, SessionStatsDto(1, 60, lastSessionAt = "2026-11-13T07:00:00.000Z"))

        assertThat(reading.positionAt).isNull()
    }

    @Test
    fun `recorded pages map without error`() {
        listOf("books-page", "books-read-along", "books-audio", "collection-books").forEach { name ->
            val page = ApiJson.decodeFromString<BooksPageDto>(Fixtures.recorded(name))
            assertThat(page.items.map(mapper::summary)).hasSize(page.items.size)
        }
        val readAlong = ApiJson.decodeFromString<BooksPageDto>(Fixtures.recorded("books-read-along"))
        readAlong.items.forEach { assertThat(mapper.summary(it).media).isEqualTo(MediaProfile.ReadAlong) }
        val detail = ApiJson.decodeFromString<BookDetailDto>(Fixtures.recorded("book"))
        assertThat(mapper.detail(detail, null).files).isNotEmpty()
    }

    @Test
    fun `a book summary carries the time of its last status change`() {
        val dto = BookCardDto(id = 1, readStatus = ReadStatusDto("reading", updatedAt = "2026-11-13T06:57:02.262Z"))

        assertThat(mapper.summary(dto).lastActivityAt).isEqualTo(Instant.parse("2026-11-13T06:57:02.262Z"))
        assertThat(mapper.summary(BookCardDto(id = 2)).lastActivityAt).isNull()
    }
}
