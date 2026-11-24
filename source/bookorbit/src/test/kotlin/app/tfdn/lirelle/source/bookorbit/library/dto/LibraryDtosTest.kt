package app.tfdn.lirelle.source.bookorbit.library.dto

import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The models must read what the real server sends. Fixtures are recorded and anonymised. */
class LibraryDtosTest {

    private inline fun <reified T> recorded(name: String): T = ApiJson.decodeFromString(Fixtures.recorded(name))

    @Test
    fun `book pages`() {
        listOf("books-page", "books-read-along", "books-audio", "books-reading", "collection-books", "author-books")
            .forEach { name ->
                val page = recorded<BooksPageDto>(name)
                assertThat(page.total).isAtLeast(page.items.size)
                page.items.forEach { card ->
                    assertThat(card.id).isGreaterThan(0)
                    assertThat(card.coverAspectRatio).isAnyOf("2/3", "1/1")
                    assertThat(card.files).isNotEmpty()
                }
            }
    }

    @Test
    fun `read-along books carry an available media overlay`() {
        val page = recorded<BooksPageDto>("books-read-along")

        assertThat(page.items).isNotEmpty()
        page.items.forEach { card ->
            assertThat(card.files.any { it.format == "epub" && it.mediaOverlay?.available == true }).isTrue()
        }
    }

    @Test
    fun `books being read carry a status`() {
        recorded<BooksPageDto>("books-reading").items.forEach { card ->
            assertThat(card.readStatus?.status).isEqualTo("reading")
        }
    }

    @Test
    fun `series page and series books`() {
        val page = recorded<SeriesPageDto>("series-page")
        assertThat(page.items).isNotEmpty()
        page.items.forEach {
            assertThat(it.name).isNotEmpty()
            assertThat(it.readCount).isAtMost(it.bookCount)
        }

        val books = recorded<SeriesBooksPageDto>("series-books")
        assertThat(books.seriesInfo.name).isNotEmpty()
        assertThat(books.items).isNotEmpty()
    }

    @Test
    fun `authors page and author`() {
        val page = recorded<AuthorsPageDto>("authors-page")
        assertThat(page.items).isNotEmpty()
        page.items.forEach { assertThat(it.name).isNotEmpty() }

        assertThat(recorded<AuthorSummaryDto>("author").name).isNotEmpty()
    }

    @Test
    fun `shelves`() {
        assertThat(ApiJson.decodeFromString<List<CollectionDto>>(Fixtures.recorded("collections"))).isNotEmpty()
        assertThat(ApiJson.decodeFromString<List<SmartScopeDto>>(Fixtures.recorded("smart-scopes"))).isNotEmpty()
    }

    @Test
    fun `book detail`() {
        val book = recorded<BookDetailDto>("book")

        assertThat(book.title).isNotNull()
        assertThat(book.authors).isNotEmpty()
        assertThat(book.files).isNotEmpty()
        assertThat(book.files.count { it.role == "primary" }).isEqualTo(1)
        assertThat(book.addedAt).isNotEmpty()
    }

    @Test
    fun `book in a series names its series`() {
        val book = recorded<BookDetailDto>("book-in-series")

        assertThat(book.seriesName).isNotNull()
        assertThat(book.seriesId).isNotNull()
    }

    @Test
    fun `reading progress, sessions and related books`() {
        assertThat(recorded<ReadingProgressListDto>("book-reading-progress").items).isNotEmpty()
        assertThat(recorded<SessionsDto>("book-sessions").stats.totalSessions).isAtLeast(0)
        ApiJson.decodeFromString<List<RelatedBookDto>>(Fixtures.recorded("book-recommendations"))
        ApiJson.decodeFromString<List<RelatedBookDto>>(Fixtures.recorded("book-author-books"))
        ApiJson.decodeFromString<List<RelatedBookDto>>(Fixtures.recorded("book-series-books"))
    }

    @Test
    fun `unknown fields and missing optional fields are tolerated`() {
        val card = ApiJson.decodeFromString<BookCardDto>("""{"id":7,"somethingNew":{"a":1}}""")

        assertThat(card.id).isEqualTo(7)
        assertThat(card.title).isNull()
        assertThat(card.authors).isEmpty()
        assertThat(card.files).isEmpty()
        assertThat(card.coverAspectRatio).isEqualTo("2/3")
    }

    @Test
    fun `a dashboard row carries full book cards and an optional total`() {
        val upNext = recorded<ScrollerDto>("home-up-next")
        val wanted = recorded<ScrollerDto>("home-want-to-read")

        assertThat(upNext.total).isNull()
        assertThat(upNext.books.first().files).isNotEmpty()
        assertThat(upNext.books.first().seriesIndex).isNotNull()
        assertThat(wanted.total).isNotNull()
    }

    @Test
    fun `a reading book says when its status last changed`() {
        val row = recorded<ScrollerDto>("home-continue-reading")

        assertThat(row.books.first().readStatus?.updatedAt).isNotNull()
    }

    @Test
    fun `the search answer has one section per kind`() {
        val search = recorded<SearchDto>("search")

        assertThat(search.series.items).isNotEmpty()
        assertThat(search.series.total).isAtLeast(search.series.items.size)
        assertThat(search.series.items.first().item.name).isNotEmpty()
        assertThat(search.books.total).isAtLeast(search.books.items.size)
    }

    @Test
    fun `the genre distribution lists genres with their counts`() {
        val genres = recorded<GenreDistributionDto>("genres")

        assertThat(genres.items).isNotEmpty()
        assertThat(genres.items.first().count).isGreaterThan(0)
    }
}
