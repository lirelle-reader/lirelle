package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedAccount
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.itemsPath
import app.tfdn.lirelle.source.audiobookshelf.testing.livresId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Hitchhiker's Guide to the Galaxy, an audiobook with an EPUB, the first of its series, being read (92 %), as
 * recorded on 2.38.0.
 */
private const val H2g2 = "4c18e748-4cde-4876-8cac-4d20a40e0da7"

/** The Hitchhiker's Guide to the Galaxy as recorded on 2.26.3. */
private const val H2g2Minimum = "d4563364-a370-4364-8426-dfca7f69ef1b"

/** Its series, as recorded on 2.38.0. */
private const val H2g2Series = "216187c2-625f-4ac7-a128-5b09296826f1"

/** Le Petit Prince, of no series, read. */
private const val PetitPrince = "f7168176-f295-4fee-b0cc-37e190ef167c"

/**
 * A book's page from its whole item, the account's reading of it from its progress, and the other books of its
 * first series, in their places.
 */
class AbsBookPagesTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var rig: BrowseRig

    @Before
    fun setUp() {
        server.start()
        answer(RecordedOn.Latest)
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** The recorded account of [on], its two whole items, and the items of The Hitchhiker's Guide's series. */
    private fun answer(on: RecordedOn) {
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes, on)
        val h2g2 = if (on == RecordedOn.Latest) H2g2 else H2g2Minimum
        routes.answer("/api/items/$h2g2", fixtureBody("item-h2g2-expanded", on))
        routes.answer("/api/items/$PetitPrince", fixtureBody("item-petit-prince-expanded", on))
        routes.answer(itemsPath(livresId(on)), fixtureBody("items-series-h2g2", on))
        rig = BrowseRig(server)
    }

    private fun pages() = AbsBookPages(rig.profileServer, rig.progress, rig.whole)

    @Test
    fun `a book's page is its whole item, in its library, read as the account's progress says, on both versions`() =
        runBlocking<Unit> {
            for (on in RecordedOn.entries) {
                answer(on)
                val id = if (on == RecordedOn.Latest) H2g2 else H2g2Minimum

                val page = (pages().book(BookId(id)) as ApiResult.Success<BookDetail>).value

                assertThat(page.id.value).isEqualTo(id)
                assertThat(page.title).isEqualTo("The Hitchhiker's Guide to the Galaxy")
                assertThat(page.authors.map { it.name }).containsExactly("Douglas Adams")
                assertThat(page.series?.name).isEqualTo("Hitchhiker's Guide to the Galaxy")
                assertThat(page.series?.index).isEqualTo("1")
                assertThat(page.series?.id).isNotNull()
                assertThat(page.genres).containsExactly("Humour", "Science-fiction").inOrder()
                assertThat(page.publishedYear).isEqualTo(1979)
                assertThat(page.publisher).isEqualTo("Del Rey")
                assertThat(page.language).isEqualTo("en")
                assertThat(page.media).isEqualTo(MediaProfile.TextAndAudio)
                assertThat(page.libraryName).isEqualTo("Livres")
                assertThat(page.readState).isEqualTo(ReadState.Reading)
                assertThat(page.coverUrl).contains("/api/items/$id/cover")
                // Nothing to read nor to listen to yet (#68, #69): no file is listed.
                assertThat(page.files).isEmpty()
                val asked = routes.requests("/api/items/$id").single().url
                assertThat(asked.queryParameter("expanded")).isEqualTo("1")
            }
        }

    @Test
    fun `a book the account may not see, or that is not there, is not found`() = runBlocking<Unit> {
        routes.fail("/api/items/$H2g2", 403)
        assertThat(pages().book(BookId(H2g2))).isEqualTo(ApiResult.Failure(ApiError.NotFound))

        assertThat(pages().book(BookId("8f0c4e1a-0000-4000-8000-000000000000")))
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
        // An id that cannot be one of Audiobookshelf's asks nothing.
        assertThat(pages().book(BookId("a/b"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(routes.requests().none { it.url.encodedPath.startsWith("/api/items/a") }).isTrue()
    }

    @Test
    fun `the account's reading of a book is its progress, the furthest of audio and ebook`() = runBlocking<Unit> {
        val reading = (pages().bookReading(BookId(H2g2)) as ApiResult.Success<BookReading>).value

        assertThat(reading.progressPercent).isEqualTo(92)
        assertThat(reading.lastReadAt).isEqualTo(Instant.ofEpochMilli(1795697669090))
        // Audiobookshelf keeps no reading sessions that Lirelle reads.
        assertThat(reading.sessionCount).isEqualTo(0)
        assertThat(reading.totalSeconds).isEqualTo(0)
    }

    @Test
    fun `a book the account has no record of has no reading`() = runBlocking<Unit> {
        val unknown = BookId("8f0c4e1a-0000-4000-8000-000000000000")
        val reading = (pages().bookReading(unknown) as ApiResult.Success<BookReading>).value

        assertThat(reading.hasAny).isFalse()
        assertThat(reading.lastReadAt).isNull()
    }

    @Test
    fun `a book page's load reads the account's progress once`() = runBlocking<Unit> {
        val pages = pages()

        pages.book(BookId(H2g2))
        pages.bookReading(BookId(H2g2))
        pages.relatedBooks(BookId(H2g2))

        assertThat(routes.requests("/api/me")).hasSize(1)
    }

    @Test
    fun `the other books of its first series are related, in their places, the book left out`() = runBlocking<Unit> {
        val related = pages().relatedBooks(BookId(H2g2))

        assertThat(related.inSeries.map { it.title }).containsExactly(
            "The Ultimate Hitchhiker’s Guide to the Galaxy",
            "The Restaurant at the End of the Universe",
            "Life, the Universe and Everything",
            "So Long, and Thanks For All the Fish",
            "Mostly Harmless",
            "And Another Thing…",
        ).inOrder()
        assertThat(related.inSeries.map { it.seriesIndex }).containsExactly("0.5", "2", "3", "4", "5", "6").inOrder()
        assertThat(related.inSeries.first { it.title.startsWith("Life") }.readState).isEqualTo(ReadState.Read)
        assertThat(related.inSeries.first().authors).containsExactly("Douglas Adams")
        assertThat(related.inSeries.first().coverUrl).contains("/api/items/c7953dfd-")
        assertThat(related.sameAuthor).isEmpty()
        assertThat(related.similar).isEmpty()
        val asked = routes.requests(itemsPath(livresId(RecordedOn.Latest))).single().url
        val filter = "series." + Base64.getEncoder().encodeToString(H2g2Series.toByteArray())
        assertThat(asked.queryParameter("filter")).isEqualTo(filter)
        assertThat(asked.queryParameter("sort")).isEqualTo("sequence")
        assertThat(asked.queryParameter("limit")).isEqualTo("0")
    }

    @Test
    fun `a book of no series has no related books, and its series is not asked`() = runBlocking<Unit> {
        assertThat(pages().relatedBooks(BookId(PetitPrince))).isEqualTo(RelatedBooks())
        assertThat(routes.requests(itemsPath(livresId(RecordedOn.Latest)))).isEmpty()
    }

    @Test
    fun `related books that cannot be had are none, never an error`() = runBlocking<Unit> {
        routes.fail(itemsPath(livresId(RecordedOn.Latest)), 500)
        assertThat(pages().relatedBooks(BookId(H2g2))).isEqualTo(RelatedBooks())

        routes.fail("/api/items/$H2g2", 403)
        assertThat(pages().relatedBooks(BookId(H2g2))).isEqualTo(RelatedBooks())
    }
}
