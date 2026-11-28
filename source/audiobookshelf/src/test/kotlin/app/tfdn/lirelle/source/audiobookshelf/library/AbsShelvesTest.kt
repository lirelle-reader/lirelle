package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedAccount
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.livresId
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedObject
import app.tfdn.lirelle.source.audiobookshelf.testing.with
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The shelves: each book library's collections and the account's playlists, a podcast's episodes left out; a
 * shelf's books in its order, sorted and narrowed on the device.
 */
class AbsShelvesTest {

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

    /** The recorded shelves of [on], the playlist given a podcast's episode, and a podcast library's playlist. */
    private fun answer(on: RecordedOn) {
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes, on)
        val libraries = recordedObject("libraries", on).getValue("libraries").jsonArray.map { it.jsonObject }
        val podcasts = libraries.first { (it.getValue("mediaType") as JsonPrimitive).content == "podcast" }
        val podcastsId = (podcasts.getValue("id") as JsonPrimitive).content
        libraries.forEach { library ->
            val id = (library.getValue("id") as JsonPrimitive).content
            val collections = if (id == livresId(on)) fixtureBody("collections-livres", on) else NoCollections
            routes.answer("/api/libraries/$id/collections", collections)
        }
        val recorded = recordedObject("playlists", on).getValue("playlists").jsonArray.single().jsonObject
        val playlist = recorded.with("items", JsonArray(recorded.getValue("items").jsonArray + episode(podcastsId)))
        val podcastList = JsonObject(
            mapOf(
                "id" to JsonPrimitive("podcast-list"),
                "name" to JsonPrimitive("Shows"),
                "libraryId" to JsonPrimitive(podcastsId),
                "items" to JsonArray(listOf(episode(podcastsId))),
            ),
        )
        val playlists = JsonObject(mapOf("playlists" to JsonArray(listOf(playlist, podcastList))))
        routes.answer("/api/playlists", playlists.toString())
        routes.answer("/api/playlists/${playlist.id}", playlist.toString())
        val collection = recordedObject("collection-classiques", on)
        routes.answer("/api/collections/${collection.id}", collection.toString())
        rig = BrowseRig(server)
    }

    private fun shelvesOf() = AbsShelves(rig.profileServer, rig.progress, rig.whole, pageSize = 2)

    @Test
    fun `the shelves are the book libraries' collections and the account's playlists of books`() {
        RecordedOn.entries.forEach { on ->
            answer(on)
            val collection = recordedObject("collection-classiques", on).id
            val playlist = recordedObject("playlists", on).getValue("playlists").jsonArray.single().jsonObject.id

            val shelves = runBlocking { shelvesOf().shelves() }

            assertThat(shelves).isEqualTo(
                ApiResult.Success(
                    Shelves(
                        smartLists = emptyList(),
                        collections = listOf(
                            Shelf(ShelfKind.Collection, collection.asCollectionShelfId(), "Classiques", 3),
                        ),
                        playlists = listOf(
                            Shelf(ShelfKind.Playlist, playlist.asPlaylistShelfId(), "À écouter en voiture", 4),
                        ),
                    ),
                ),
            )
            assertThat(collection.asCollectionShelfId().value).isEqualTo("c:$collection")
            assertThat(playlist.asPlaylistShelfId().value).isEqualTo("p:$playlist")
        }
    }

    @Test
    fun `the collections are asked of each book library, whole, and of no podcast library`() = runBlocking<Unit> {
        shelvesOf().shelves()

        val asked = routes.requests().filter { it.url.encodedPath.endsWith("/collections") }
        assertThat(asked.map { it.url.encodedPath }).containsExactly(
            "/api/libraries/$LivresId/collections",
            "/api/libraries/$BdId/collections",
        ).inOrder()
        assertThat(asked.map { it.url.queryParameter("limit") }.distinct()).containsExactly("0")
    }

    private suspend fun titles(id: ShelfId, request: BookListRequest = BookListRequest()): List<String> =
        shelvesOf().books(BookListSource.Collection(id), request).items.asSnapshot { appendScrollWhile { true } }
            .map(BookSummary::title)

    @Test
    fun `a collection's books are in its order, which is the list newest first`() = runBlocking<Unit> {
        val id = recordedObject("collection-classiques").id.asCollectionShelfId()

        assertThat(titles(id)).containsExactly("Le Petit Prince", "Les Indes noires", "Le scarabée d'or").inOrder()
        assertThat(titles(id, BookListRequest(BookSort.Added, SortDirection.Ascending)))
            .containsExactly("Le scarabée d'or", "Les Indes noires", "Le Petit Prince").inOrder()
        assertThat(titles(id, BookListRequest(BookSort.Title, SortDirection.Ascending)))
            .containsExactly("Le Petit Prince", "Le scarabée d'or", "Les Indes noires").inOrder()
    }

    @Test
    fun `a shelf's books are narrowed on the device by status and by text`() = runBlocking<Unit> {
        val id = recordedObject("collection-classiques").id.asCollectionShelfId()
        val read = BookListRequest(filters = BookFilters(statuses = setOf(StatusFilter.Read)))

        assertThat(titles(id, read)).containsExactly("Le Petit Prince")
        assertThat(titles(id, BookListRequest(text = "SCARABEE"))).containsExactly("Le scarabée d'or")
    }

    @Test
    fun `a playlist's books are in its order, a podcast's episodes left out`() {
        RecordedOn.entries.forEach { on ->
            answer(on)
            val id = recordedObject("playlists", on).getValue("playlists").jsonArray.single().jsonObject.id

            val books = runBlocking { titles(id.asPlaylistShelfId()) }

            assertThat(books).containsExactly("Project Hail Mary", "The Martian", "All Systems Red", "Red Mars")
                .inOrder()
        }
    }

    @Test
    fun `a smart list or a shelf of no Audiobookshelf id has no books, and nothing is asked`() = runBlocking<Unit> {
        val before = routes.requests().size

        assertThat(shelvesOf().books(BookListSource.SmartList(ShelfId("c:x")), BookListRequest()).items.asSnapshot())
            .isEmpty()
        assertThat(titles(ShelfId("x:y"))).isEmpty()
        assertThat(routes.requests()).hasSize(before)
    }

    private companion object {
        const val NoCollections = """{"results": [], "total": 0}"""
    }
}

/** A podcast's episode in a playlist, of the podcast library [libraryId]. */
private fun episode(libraryId: String): JsonObject = Json.parseToJsonElement(
    """
    {"libraryItemId": "podcast-item", "episodeId": "episode-1",
     "libraryItem": {"id": "podcast-item", "libraryId": "$libraryId", "mediaType": "podcast",
                     "media": {"metadata": {"title": "A show"}}}}
    """,
).jsonObject

/** The id of a recorded object. */
private val JsonObject.id: String get() = (getValue("id") as JsonPrimitive).content
