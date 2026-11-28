package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.HomeRig
import app.tfdn.lirelle.source.audiobookshelf.testing.InProgressPath
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedHome
import app.tfdn.lirelle.source.audiobookshelf.testing.personalizedPath
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedObject
import app.tfdn.lirelle.source.audiobookshelf.testing.with
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val H2g2 = "4c18e748-4cde-4876-8cac-4d20a40e0da7"

/** A library that is not among the account's. */
private const val ClosedLibrary = "0d9f3c1e-7a52-4b8e-9f61-3c2a8e4d7b10"

/** When the reader last listened to The Hitchhiker's Guide to the Galaxy, as items-in-progress says. */
private const val H2g2LastUpdate = 1_795_697_669_090L

/**
 * Home's Continue row: the items the account has started, of its book libraries, in the server's order, a
 * podcast's episode left out; Want to read, which Audiobookshelf has not.
 */
class AbsHomeTest {

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

    @Test
    fun `Continue lists the books in progress in the server's order, without an episode`() = runBlocking<Unit> {
        val row = row(HomeRowKind.Continue)

        assertThat(row.books.map { it.title }).containsExactly(
            "The Hitchhiker's Guide to the Galaxy",
            "Project Hail Mary",
            "Les Indes noires",
            "Astérix 1",
            "Parlons Espéranto [fr] (1998)",
        ).inOrder()
        assertThat(row.hasMore).isFalse()
        assertThat(row.playback).isEmpty()
        val h2g2 = row.books.first()
        assertThat(h2g2.id).isEqualTo(BookId(H2g2))
        assertThat(h2g2.readState).isEqualTo(ReadState.Reading)
        assertThat(h2g2.progressPercent).isEqualTo(92)
        assertThat(h2g2.lastActivityAt).isEqualTo(Instant.ofEpochMilli(H2g2LastUpdate))
        assertThat(routes.requests(InProgressPath).single().url.queryParameter("limit")).isEqualTo("20")
        assertThat(routes.requests(personalizedPath(LivresId))).isEmpty()
        assertThat(routes.requests(personalizedPath(BdId))).isEmpty()
    }

    @Test
    fun `Continue says there are more when the server gave as many as it was asked`() = runBlocking<Unit> {
        val many = recordedObject("items-livres-all").getValue("results").jsonArray.take(20)
        routes.answer(InProgressPath, JsonObject(mapOf("libraryItems" to JsonArray(many))).toString())

        val row = row(HomeRowKind.Continue)

        assertThat(row.books).hasSize(20)
        assertThat(row.hasMore).isTrue()
    }

    @Test
    fun `Continue leaves out the books of a library the account cannot open`() = runBlocking<Unit> {
        // 2.26.3 does not check the account's access to the libraries of the items in progress.
        val items = recordedObject("items-in-progress").getValue("libraryItems").jsonArray.map { it.jsonObject }
        val elsewhere = items.mapIndexed { index, item ->
            when (index) {
                0 -> item.with("libraryId", JsonPrimitive(ClosedLibrary))
                2 -> JsonObject(item - "libraryId")
                else -> item
            }
        }
        routes.answer(InProgressPath, JsonObject(mapOf("libraryItems" to JsonArray(elsewhere))).toString())

        val row = row(HomeRowKind.Continue)

        assertThat(row.books.map { it.title })
            .containsExactly("Les Indes noires", "Astérix 1", "Parlons Espéranto [fr] (1998)").inOrder()
    }

    @Test
    fun `Continue's See all asks for 50`() = runBlocking<Unit> {
        row(HomeRowKind.Continue, HomeRowExtent.SeeAll)

        assertThat(routes.requests(InProgressPath).single().url.queryParameter("limit")).isEqualTo("50")
    }

    @Test
    fun `a book of the row is being read and dated by the row itself when the account's progress lags`() =
        runBlocking<Unit> {
            routes.answer("/api/me", """{"mediaProgress": []}""")

            val h2g2 = row(HomeRowKind.Continue).books.first()

            assertThat(h2g2.readState).isEqualTo(ReadState.Reading)
            assertThat(h2g2.progressPercent).isNull()
            assertThat(h2g2.lastActivityAt).isEqualTo(Instant.ofEpochMilli(H2g2LastUpdate))
        }

    @Test
    fun `Continue reads 2_26_3's answer`() = runBlocking<Unit> {
        routes = LibraryRoutes(server)
        answerRecordedHome(routes, RecordedOn.Minimum)

        val row = row(HomeRowKind.Continue)

        assertThat(row.books.map { it.title }).hasSize(5)
        assertThat(row.books.first().progressPercent).isEqualTo(92)
    }

    @Test
    fun `a row that fails shows the error of its status`() = runBlocking<Unit> {
        routes.fail(InProgressPath, 500)

        assertThat(rig.home.homeRow(HomeRowKind.Continue, HomeRowExtent.Row))
            .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `Want to read is empty, and asks nothing`() = runBlocking<Unit> {
        val row = row(HomeRowKind.WantToRead)

        assertThat(row.books).isEmpty()
        assertThat(row.hasMore).isFalse()
        assertThat(routes.requests()).isEmpty()
    }
}
