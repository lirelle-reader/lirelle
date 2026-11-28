package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.Livres
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedAccount
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.itemsPath
import app.tfdn.lirelle.source.audiobookshelf.testing.livresId
import app.tfdn.lirelle.source.audiobookshelf.testing.pagedSeries
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedObject
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedSeries
import app.tfdn.lirelle.source.audiobookshelf.testing.routeAnswer
import app.tfdn.lirelle.source.audiobookshelf.testing.seriesPath
import app.tfdn.lirelle.source.audiobookshelf.testing.with
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A library's series, asked by pages until the server's total (never `limit=0`, which answers none), each with its
 * books' covers, count and read count; a series' page, its books in their order.
 */
class AbsSeriesTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var rig: BrowseRig

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes)
        routes.answer(seriesPath(LivresId), pagedSeries(recordedSeries()))
        rig = BrowseRig(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun seriesOf(pageSize: Int = 100) = AbsSeries(rig.profileServer, rig.progress, rig.whole, pageSize)

    @Test
    fun `the series are asked by name, a hundred at a time, each with its books' covers and count`() =
        runBlocking<Unit> {
            val series = (seriesOf().series(text = null, library = Livres) as ApiResult.Success).value

            assertThat(series.map { it.name }).containsExactly(
                "A Game of Thrones",
                "A Series of Unfortunate Events",
                "Cantos d'Hyperion",
                "Charlie",
                "Chronique du tueur de roi",
            ).inOrder()
            val thrones = series.first()
            assertThat(thrones.id.value).isEqualTo("28d0f741-d775-4934-8884-25fd4271a1c3")
            assertThat(thrones.bookCount).isEqualTo(5)
            assertThat(thrones.readCount).isEqualTo(0)
            assertThat(thrones.authors).containsExactly("George R.R. Martin")
            assertThat(thrones.coverUrls).hasSize(3)
            assertThat(thrones.coverUrls.first()).contains("/api/items/8cc3b314-")
            assertThat(thrones.lastAddedAt?.toEpochMilli()).isEqualTo(1795783844249)
            val asked = routes.requests(seriesPath(LivresId)).single().url
            assertThat(asked.queryParameter("limit")).isEqualTo("100")
            assertThat(asked.queryParameter("page")).isEqualTo("0")
            assertThat(asked.queryParameter("sort")).isEqualTo("name")
        }

    @Test
    fun `the pages are asked until the total is reached, never the whole list at once`() = runBlocking<Unit> {
        val series = (seriesOf(pageSize = 2).series(text = null, library = Livres) as ApiResult.Success).value

        assertThat(series).hasSize(5)
        val pages = routes.requests(seriesPath(LivresId)).map { it.url }
        assertThat(pages.map { it.queryParameter("page") }).containsExactly("0", "1", "2").inOrder()
        assertThat(pages.map { it.queryParameter("limit") }.distinct()).containsExactly("2")
    }

    @Test
    fun `a series counts its books the account has read`() = runBlocking<Unit> {
        val books = recordedObject("items-series-h2g2").getValue("results")
        val h2g2 = recordedObject("series-h2g2").with("books", books)
        routes.answer(seriesPath(LivresId), pagedSeries(JsonArray(listOf(h2g2))))

        val series = (seriesOf().series(text = null, library = Livres) as ApiResult.Success).value.single()

        assertThat(series.bookCount).isEqualTo(7)
        assertThat(series.readCount).isEqualTo(1)
    }

    @Test
    fun `a text narrows the series by name on the device, case and accents ignored`() = runBlocking<Unit> {
        suspend fun names(text: String) =
            (seriesOf().series(text, Livres) as ApiResult.Success).value.map(Series::name)

        assertThat(names(" CHRONIQUÉ ")).containsExactly("Chronique du tueur de roi")
        assertThat(names("series")).containsExactly("A Series of Unfortunate Events")
        assertThat(names("  ")).hasSize(5)
    }

    @Test
    fun `a library the account may not see is not found`() = runBlocking<Unit> {
        routes.fail(seriesPath(LivresId), 403)

        assertThat(seriesOf().series(text = null, library = Livres)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `a series' page lists its books in their order, by the server's series filter`() {
        RecordedOn.entries.forEach { on ->
            val books = seriesBooks(on)

            assertThat(books.series.name).isEqualTo("Hitchhiker's Guide to the Galaxy")
            assertThat(books.series.bookCount).isEqualTo(7)
            assertThat(books.series.readCount).isEqualTo(1)
            assertThat(books.books.map { it.series?.index })
                .containsExactly("0.5", "1", "2", "3", "4", "5", "6").inOrder()
            assertThat(books.books.map { it.series?.id }.distinct()).containsExactly(books.series.id)
            assertThat(books.books.first { it.title == "Life, the Universe and Everything" }.readState)
                .isEqualTo(ReadState.Read)
            val asked = routes.requests(itemsPath(livresId(on))).single().url
            val seriesId = books.series.id.value
            assertThat(asked.queryParameter("filter"))
                .isEqualTo("series." + Base64.getEncoder().encodeToString(seriesId.toByteArray()))
            assertThat(asked.queryParameter("sort")).isEqualTo("sequence")
            assertThat(asked.queryParameter("limit")).isEqualTo("0")
        }
    }

    private fun seriesBooks(on: RecordedOn): SeriesBooks = runBlocking {
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes, on)
        val one = recordedObject("series-h2g2", on)
        val id = (one.getValue("id") as JsonPrimitive).content
        routes.answer("/api/series/$id", one.toString())
        routes.answer(itemsPath(livresId(on))) { routeAnswer(fixtureBody("items-series-h2g2", on)) }
        rig = BrowseRig(server)
        (seriesOf().seriesBooks(id.asSeriesId()) as ApiResult.Success).value
    }

    @Test
    fun `a series the server does not know, or an id that is none of its, is not found`() = runBlocking<Unit> {
        assertThat(seriesOf().seriesBooks("nowhere".asSeriesId())).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(seriesOf().seriesBooks("a/b".asSeriesId())).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(routes.requests().map { it.url.encodedPath }).containsExactly("/api/series/nowhere")
    }
}
