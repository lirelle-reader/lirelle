package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.FileDto
import app.tfdn.lirelle.source.kavita.library.dto.GenreDto
import app.tfdn.lirelle.source.kavita.library.dto.PersonRefDto
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListItemDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesMetadataDto
import app.tfdn.lirelle.source.kavita.library.dto.VolumeDto
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.testing.FakeTitles
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

private const val Server = "https://kavita.example/base"

class KavitaMapperTest {

    private val mapper = KavitaMapper(Server, FakeTitles)

    private inline fun <reified T> read(name: String): T = KavitaJson.decodeFromString<T>(fixtureBody(name))

    private fun book(chapter: ChapterDto) = chapter.book(volumeIndex = null)

    @Test
    fun `a book takes Kavita's title, else one of its own`() {
        val asterix = read<ChapterDto>("chapter-asterix")
        val untitled = ChapterDto(id = 5, number = "12", range = "12", titleName = " ")
        val volume = ChapterDto(id = 6, number = DefaultChapter, range = DefaultChapter, titleName = "", volumeId = 3)
        val special = ChapterDto(id = 7, number = DefaultChapter, range = "HS", isSpecial = true, title = "Hors-série")
        val bareSpecial = ChapterDto(id = 8, number = DefaultChapter, range = "HS", isSpecial = true)

        assertThat(mapper.title(book(asterix))).isEqualTo("Astérix le Gaulois")
        assertThat(mapper.title(book(untitled))).isEqualTo("Chapitre 12")
        assertThat(mapper.title(volume.book(volumeIndex = "3"))).isEqualTo("Tome 3")
        assertThat(mapper.title(book(special))).isEqualTo("Hors-série")
        assertThat(mapper.title(book(bareSpecial))).isEqualTo("Chapitre HS")
    }

    @Test
    fun `a book without a number is untitled, whatever Kavita's other title says`() {
        val volume = ChapterDto(id = 6, number = DefaultChapter, range = DefaultChapter, volumeId = 3)
        val chapter = ChapterDto(id = 5, number = "", range = " ")
        val special = ChapterDto(id = 7, number = DefaultChapter, range = "", isSpecial = true)

        assertThat(mapper.title(volume.book(volumeIndex = null))).isEqualTo("Sans titre")
        assertThat(mapper.title(volume.copy(title = "Book -100000").book(volumeIndex = " "))).isEqualTo("Sans titre")
        assertThat(mapper.title(volume.copy(title = "12").book(volumeIndex = null))).isEqualTo("Sans titre")
        assertThat(mapper.title(volume.copy(title = "Book Dune").book(volumeIndex = null))).isEqualTo("Sans titre")
        assertThat(mapper.title(book(chapter))).isEqualTo("Sans titre")
        assertThat(mapper.title(book(special))).isEqualTo("Sans titre")
    }

    @Test
    fun `the read state follows the pages read`() {
        fun state(read: Int) = mapper.summary(book(ChapterDto(id = 1, pages = 10, pagesRead = read)), null)

        assertThat(state(0).readState).isEqualTo(ReadState.Unread)
        assertThat(state(0).progressPercent).isNull()
        assertThat(state(4).readState).isEqualTo(ReadState.Reading)
        assertThat(state(4).progressPercent).isEqualTo(40)
        assertThat(state(10).readState).isEqualTo(ReadState.Read)
        assertThat(state(10).progressPercent).isEqualTo(100)
        assertThat(state(12).progressPercent).isEqualTo(100)
    }

    @Test
    fun `the last reading is a time in UTC, and year 1 is none`() {
        val dune = mapper.summary(read<ChapterDto>("continue-dune").book(volumeIndex = "1"), null)
        val asterix = mapper.summary(book(read("chapter-asterix")), null)

        assertThat(dune.lastActivityAt).isEqualTo(Instant.parse("2026-11-26T08:27:17.5600054Z"))
        assertThat(asterix.lastActivityAt).isNull()
    }

    @Test
    fun `covers are the server's image routes, without a key`() {
        val asterix = mapper.summary(book(read("chapter-asterix")), null)
        val dune = mapper.summary(read<ChapterDto>("continue-dune").book(volumeIndex = "1"), null)
        val series = mapper.series(read("series-dune"))

        assertThat(asterix.coverUrl).isEqualTo("$Server/api/Image/chapter-cover?chapterId=44")
        assertThat(dune.coverUrl).isEqualTo("$Server/api/Image/volume-cover?volumeId=407")
        assertThat(series.coverUrls).containsExactly("$Server/api/Image/series-cover?seriesId=261")
        assertThat((listOf(asterix.coverUrl, dune.coverUrl) + series.coverUrls).none { "apiKey" in it.orEmpty() })
            .isTrue()
    }

    @Test
    fun `a book in a list carries its series, its people and what it is read in`() {
        val asterix = mapper.summary(book(read("chapter-asterix")), SeriesRefInfo(7, "Astérix"))

        assertThat(asterix.id).isEqualTo(BookId("c44"))
        assertThat(asterix.series).isEqualTo(SeriesRef(SeriesId("7"), "Astérix", "1"))
        assertThat(asterix.authors).containsExactly("Albert Uderzo", "René Goscinny").inOrder()
        assertThat(asterix.media).isEqualTo(MediaProfile.TextOnly)
        assertThat(asterix.coverShape).isEqualTo(CoverShape.Portrait)
        assertThat(asterix.pagedKind).isEqualTo(PagedKind.Comic)
    }

    @Test
    fun `what a book is read in comes from its file, else its chapter, else its series`() {
        fun kind(chapter: ChapterDto, seriesFormat: Int? = null) =
            mapper.summary(book(chapter), null, seriesFormat).pagedKind

        assertThat(kind(ChapterDto(id = 1, format = 3, files = listOf(FileDto(format = 4))))).isEqualTo(PagedKind.Pdf)
        assertThat(kind(ChapterDto(id = 1, format = 0))).isEqualTo(PagedKind.Comic)
        assertThat(kind(ChapterDto(id = 1, format = 3))).isNull()
        assertThat(kind(ChapterDto(id = 1), seriesFormat = 1)).isEqualTo(PagedKind.Comic)
        assertThat(kind(ChapterDto(id = 1))).isNull()
    }

    @Test
    fun `a lone book, or a special named as its series, has no series`() {
        val lone = read<ChapterDto>("chapter-lone")

        assertThat(mapper.summary(book(lone), null).series).isNull()
        assertThat(mapper.summary(book(lone), SeriesRefInfo(372, "11/22/63")).series).isNull()
        assertThat(mapper.summary(book(lone), SeriesRefInfo(372, "Stephen King")).series)
            .isEqualTo(SeriesRef(SeriesId("372"), "Stephen King", null))
    }

    @Test
    fun `a reading list's items are books without another call`() {
        val items = read<List<ReadingListItemDto>>("readinglist-items").map(mapper::readingListItem)

        val dune = items[0]
        assertThat(dune.id).isEqualTo(BookId("v407"))
        assertThat(dune.title).isEqualTo("Dune")
        assertThat(dune.authors).containsExactly("Frank Herbert")
        assertThat(dune.series).isEqualTo(SeriesRef(SeriesId("261"), "Dune", "1"))
        assertThat(dune.coverUrl).isEqualTo("$Server/api/Image/volume-cover?volumeId=407")
        assertThat(dune.readState).isEqualTo(ReadState.Read)
        assertThat(dune.pagedKind).isNull()
        val alix = items[2]
        assertThat(alix.id).isEqualTo(BookId("c899"))
        assertThat(alix.title).isEqualTo("Alix L'Intrépide")
        assertThat(alix.authors).containsExactly("Jacques Martin")
        assertThat(alix.series).isEqualTo(SeriesRef(SeriesId("68"), "Alix", "1"))
        assertThat(alix.pagedKind).isEqualTo(PagedKind.Comic)
    }

    @Test
    fun `a reading list's item without a title has one of its own`() {
        val item = ReadingListItemDto(chapterId = 3, volumeId = 2, volumeNumber = "5", chapterNumber = DefaultChapter)

        assertThat(mapper.readingListItem(item).title).isEqualTo("Tome 5")
        assertThat(mapper.readingListItem(item.copy(chapterNumber = "7")).title).isEqualTo("Chapitre 7")
        assertThat(mapper.readingListItem(item.copy(volumeNumber = null)).title).isEqualTo("Sans titre")
    }

    @Test
    fun `a reading list's default chapter outside any volume is a chapter, not the loose-leaf volume`() {
        val item = ReadingListItemDto(chapterId = 3, volumeId = 2, volumeNumber = "1", chapterNumber = DefaultChapter)

        assertThat(mapper.readingListItem(item).id).isEqualTo(BookId("v2"))
        assertThat(mapper.readingListItem(item.copy(volumeNumber = "-100000")).id).isEqualTo(BookId("c3"))
        assertThat(mapper.readingListItem(item.copy(volumeNumber = "100000")).id).isEqualTo(BookId("c3"))
        assertThat(mapper.readingListItem(item.copy(volumeNumber = "-100000")).series?.index).isNull()
    }

    @Test
    fun `a reading list's item never takes Kavita's English title, but a special does`() {
        val alix = read<List<ReadingListItemDto>>("readinglist-items")[2].copy(chapterTitleName = null)
        val special = alix.copy(isSpecial = true, title = "Hors-série", chapterNumber = DefaultChapter)

        assertThat(alix.title).isEqualTo("Issue #1")
        assertThat(mapper.readingListItem(alix).title).isEqualTo("Chapitre 1")
        assertThat(mapper.readingListItem(special).title).isEqualTo("Hors-série")
        assertThat(mapper.readingListItem(special.copy(title = null)).title).isEqualTo("Sans titre")
    }

    @Test
    fun `a comic's page tells its people, publisher, year, pages and library`() {
        val chapter = read<ChapterDto>("chapter-asterix")
        val series = read<SeriesDto>("series-asterix")

        val detail = mapper.detail(book(chapter), series, read<SeriesMetadataDto>("metadata-asterix"))

        assertThat(detail.id).isEqualTo(BookId("c44"))
        assertThat(detail.title).isEqualTo("Astérix le Gaulois")
        assertThat(detail.authors).containsExactly(
            AuthorRef(AuthorId("Albert Uderzo"), "Albert Uderzo"),
            AuthorRef(AuthorId("René Goscinny"), "René Goscinny"),
        ).inOrder()
        assertThat(detail.series).isEqualTo(SeriesRef(SeriesId("7"), "Astérix", "1"))
        assertThat(detail.publisher).isEqualTo("Dargaud")
        assertThat(detail.publishedYear).isEqualTo(1961)
        assertThat(detail.pageCount).isEqualTo(53)
        assertThat(detail.libraryName).isEqualTo("BD")
        assertThat(detail.description).startsWith("Dans le camp fortifié romain")
        assertThat(detail.isbn).isNull()
        assertThat(detail.addedAt).isEqualTo(Instant.parse("2026-11-26T07:53:46.7739865Z"))
        assertThat(detail.files).isEmpty()
        assertThat(detail.readState).isEqualTo(ReadState.Unread)
        assertThat(detail.coverUrl).isEqualTo("$Server/api/Image/chapter-cover?chapterId=44")
    }

    @Test
    fun `a lone book's page has no series`() {
        val lone = book(read("chapter-lone"))
        val series = SeriesDto(id = 372, name = "Nouvelles")

        assertThat(mapper.detail(lone, series, null, lone = true).series).isNull()
        assertThat(mapper.detail(lone, series, null).series).isEqualTo(SeriesRef(SeriesId("372"), "Nouvelles", null))
    }

    @Test
    fun `an EPUB's page gives no page count`() {
        val volume = read<VolumeDto>("volume-dune")
        val book = volume.books().single()

        val detail = mapper.detail(book, read("series-dune"), read<SeriesMetadataDto>("metadata-dune"))

        assertThat(detail.id).isEqualTo(BookId("v407"))
        assertThat(detail.pageCount).isNull()
        assertThat(detail.coverUrl).isEqualTo("$Server/api/Image/volume-cover?volumeId=407")
        assertThat(detail.authors.map { it.name }).containsExactly("Frank Herbert")
        assertThat(detail.publisher).isEqualTo("Robert Laffont")
        assertThat(detail.publishedYear).isEqualTo(1970)
        assertThat(detail.language).isEqualTo("fr")
        assertThat(detail.isbn).isEqualTo("9782221127483")
        assertThat(detail.genres).containsExactly("Science-fiction", "classiques").inOrder()
        assertThat(detail.libraryName).isEqualTo("Livres")
        assertThat(detail.readState).isEqualTo(ReadState.Read)
    }

    @Test
    fun `what the chapter does not say, the series does`() {
        val bare = ChapterDto(id = 1, pages = 3)
        val metadata = SeriesMetadataDto(
            summary = "Of the series",
            genres = listOf(GenreDto(1, "Aventure")),
            publishers = listOf(PersonRefDto(2, "Casterman")),
            language = "fr",
        )

        val detail = mapper.detail(book(bare), SeriesDto(id = 9, name = "Tintin"), metadata)
        val without = mapper.detail(book(bare), SeriesDto(id = 9, name = "Tintin"), null)

        assertThat(detail.description).isEqualTo("Of the series")
        assertThat(detail.genres).containsExactly("Aventure")
        assertThat(detail.publisher).isEqualTo("Casterman")
        assertThat(detail.language).isEqualTo("fr")
        assertThat(without.description).isNull()
        assertThat(without.genres).isEmpty()
        assertThat(without.publishedYear).isNull()
    }

    @Test
    fun `my reading of a book is its pages and its last reading`() {
        val reading = mapper.reading(read("continue-naruto"))
        val unread = mapper.reading(read("chapter-asterix"))

        assertThat(reading.progressPercent).isEqualTo(9 * 100 / 191)
        assertThat(reading.page).isEqualTo(9)
        assertThat(reading.lastReadAt).isNull()
        assertThat(reading.totalSeconds).isEqualTo(0)
        assertThat(reading.sessionCount).isEqualTo(0)
        assertThat(unread.progressPercent).isNull()
        assertThat(unread.page).isNull()
    }

    @Test
    fun `a series in a list has its cover and no counts, a series page counts its books`() {
        val dto = read<SeriesDto>("series-dune")
        val books = read<VolumeDto>("volume-dune").books().map { mapper.summary(it, null) } +
            mapper.summary(book(ChapterDto(id = 2, pages = 5)), null)

        val series = mapper.series(dto)
        val counted = mapper.seriesWithCounts(dto, books)

        assertThat(series.id).isEqualTo(SeriesId("261"))
        assertThat(series.name).isEqualTo("Dune")
        assertThat(series.bookCount).isEqualTo(0)
        assertThat(series.lastAddedAt).isEqualTo(Instant.parse("2026-11-26T08:03:03.4921207Z"))
        assertThat(counted.bookCount).isEqualTo(2)
        assertThat(counted.readCount).isEqualTo(1)
    }

    @Test
    fun `a related book has its place in the series`() {
        val related = mapper.related(read<VolumeDto>("volume-dune").books().single())

        assertThat(related.id).isEqualTo(BookId("v407"))
        assertThat(related.title).isEqualTo("Dune")
        assertThat(related.seriesIndex).isEqualTo("1")
        assertThat(related.readState).isEqualTo(ReadState.Read)
        assertThat(related.coverShape).isEqualTo(CoverShape.Portrait)
    }
}
