package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.HomeRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedHome
import app.tfdn.lirelle.source.audiobookshelf.testing.bookShelf
import app.tfdn.lirelle.source.audiobookshelf.testing.continuing
import app.tfdn.lirelle.source.audiobookshelf.testing.personalizedPath
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedObject
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedShelfEntities
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedShelves
import app.tfdn.lirelle.source.audiobookshelf.testing.title
import app.tfdn.lirelle.source.audiobookshelf.testing.withShelf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val H2g2Series = "216187c2-625f-4ac7-a128-5b09296826f1"
private const val AldebaranSeries = "7d1f0c2e-3a4b-4c5d-8e9f-0a1b2c3d4e5f"

/**
 * Home's Up next and Recently added rows: each book library's `continue-series` and `recently-added` shelves, the
 * former joined in the libraries' order, the latter merged newest first; their answers shared for a few seconds.
 */
class AbsHomeShelvesTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var rig: HomeRig

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedHome(routes)
        rig = HomeRig(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private suspend fun row(kind: HomeRowKind, extent: HomeRowExtent = HomeRowExtent.Row): HomeRow =
        (rig.home.homeRow(kind, extent) as ApiResult.Success).value

    /** Livres continues the Hitchhiker's Guide with its second book, BD Aldebaran with its first. */
    private fun answerContinueSeries() {
        val restaurant = recordedObject("items-series-h2g2").getValue("results").jsonArray.map { it.jsonObject }
            .first { it.title == "The Restaurant at the End of the Universe" }
            .continuing(H2g2Series, "Hitchhiker's Guide to the Galaxy", "2")
        val aldebaran = recordedShelfEntities("personalized-bd", "recently-added").first()
            .continuing(AldebaranSeries, "Aldebaran", "1")
        routes.answer(
            personalizedPath(LivresId),
            recordedShelves("personalized-livres").withShelf(bookShelf("continue-series", listOf(restaurant))),
        )
        routes.answer(
            personalizedPath(BdId),
            recordedShelves("personalized-bd").withShelf(bookShelf("continue-series", listOf(aldebaran))),
        )
    }

    private fun personalizedAsked(): List<Int> =
        listOf(LivresId, BdId).map { routes.requests(personalizedPath(it)).size }

    @Test
    fun `Up next joins each book library's continue-series shelf in the libraries' order`() = runBlocking<Unit> {
        answerContinueSeries()

        val row = row(HomeRowKind.UpNext)

        assertThat(row.books.map { it.title })
            .containsExactly("The Restaurant at the End of the Universe", "Aldebaran 1").inOrder()
        assertThat(row.books.first().series)
            .isEqualTo(SeriesRef(SeriesId(H2g2Series), "Hitchhiker's Guide to the Galaxy", "2"))
        assertThat(row.hasMore).isFalse()
        assertThat(routes.requests(personalizedPath(LivresId)).single().url.queryParameter("limit")).isEqualTo("20")
        assertThat(routes.requests().map { it.url.encodedPath }.filter { it.endsWith("/personalized") })
            .containsExactly(personalizedPath(LivresId), personalizedPath(BdId))
    }

    @Test
    fun `Up next is empty without a continue-series shelf, as the server answers without a series to go on`() =
        runBlocking<Unit> {
            val row = row(HomeRowKind.UpNext)

            assertThat(row.books).isEmpty()
            assertThat(row.hasMore).isFalse()
        }

    @Test
    fun `Recently added merges each library's shelf, the newest first`() = runBlocking<Unit> {
        val row = row(HomeRowKind.RecentlyAdded)

        val recorded = recordedShelfEntities("personalized-livres", "recently-added") +
            recordedShelfEntities("personalized-bd", "recently-added")
        val newestFirst = recorded.sortedByDescending { (it.getValue("addedAt") as JsonPrimitive).content.toLong() }
        assertThat(row.books.map { it.title }).containsExactlyElementsIn(newestFirst.map { it.title }).inOrder()
        // BD's books came after Livres', though Livres is the first library.
        assertThat(row.books.first().title).isEqualTo("Aldebaran 1")
        assertThat(row.hasMore).isTrue()
        val asterix = row.books.single { it.title == "Astérix 1" }
        assertThat(asterix.readState).isEqualTo(ReadState.Reading)
        assertThat(asterix.progressPercent).isEqualTo(35)
    }

    @Test
    fun `Recently added keeps as many books as it was asked, the newest`() = runBlocking<Unit> {
        val fifteen = recordedObject("items-livres-all").getValue("results").jsonArray.take(15)
        routes.answer(
            personalizedPath(LivresId),
            recordedShelves("personalized-livres").withShelf(bookShelf("recently-added", fifteen)),
        )
        val bd = recordedShelfEntities("personalized-bd", "recently-added")
        routes.answer(
            personalizedPath(BdId),
            recordedShelves("personalized-bd").withShelf(bookShelf("recently-added", bd)),
        )

        val row = row(HomeRowKind.RecentlyAdded)

        val newest = (fifteen.map { it.jsonObject } + bd)
            .sortedByDescending { (it.getValue("addedAt") as JsonPrimitive).content.toLong() }.take(20)
        assertThat(row.books.map { it.title }).containsExactlyElementsIn(newest.map { it.title }).inOrder()
        assertThat(row.hasMore).isTrue()
    }

    @Test
    fun `Up next and Recently added share each library's shelves for a few seconds, once`() = runBlocking<Unit> {
        answerContinueSeries()

        row(HomeRowKind.UpNext)
        row(HomeRowKind.RecentlyAdded)
        assertThat(personalizedAsked()).containsExactly(1, 1).inOrder()

        row(HomeRowKind.UpNext)
        assertThat(personalizedAsked()).containsExactly(2, 2).inOrder()

        rig.now += 10_001
        row(HomeRowKind.RecentlyAdded)
        assertThat(personalizedAsked()).containsExactly(3, 3).inOrder()
    }

    @Test
    fun `a See all screen asks for 50 and keeps its own shelves`() = runBlocking<Unit> {
        row(HomeRowKind.UpNext)
        row(HomeRowKind.RecentlyAdded, HomeRowExtent.SeeAll)

        assertThat(personalizedAsked()).containsExactly(2, 2).inOrder()
        assertThat(routes.requests(personalizedPath(BdId)).last().url.queryParameter("limit")).isEqualTo("50")
    }

    @Test
    fun `the rows read 2_26_3's shelves`() = runBlocking<Unit> {
        routes = LibraryRoutes(server)
        answerRecordedHome(routes, RecordedOn.Minimum)

        assertThat(row(HomeRowKind.RecentlyAdded).books).hasSize(20)
        assertThat(row(HomeRowKind.UpNext).books).isEmpty()
    }
}
