package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.HomeRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedHome
import app.tfdn.lirelle.source.audiobookshelf.testing.bookShelf
import app.tfdn.lirelle.source.audiobookshelf.testing.personalizedPath
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedShelfEntities
import app.tfdn.lirelle.source.audiobookshelf.testing.title
import app.tfdn.lirelle.source.audiobookshelf.testing.with
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Livres alone, as a server that lost BD would answer. */
private const val LivresAlone = """{"libraries":[
    {"id":"$LivresId","name":"Livres","displayOrder":1,"mediaType":"book"}]}"""

/** The recorded libraries and a library of books added since. */
private const val WithNovels = """{"libraries":[
    {"id":"$LivresId","name":"Livres","displayOrder":1,"mediaType":"book"},
    {"id":"$BdId","name":"BD","displayOrder":2,"mediaType":"book"},
    {"id":"novels","name":"Romans","displayOrder":3,"mediaType":"book"}]}"""

/**
 * Home's rows as the server changes: a library the account can no longer open is left out, one removed stops being
 * asked once the libraries are read again (a minute later), one added shows then; each load of the rows reads the
 * account's progress again.
 */
class AbsHomeChangesTest {

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

    /** Time goes by: Home's shelves are no longer shared, and the libraries are read again. */
    private fun aMinuteLater() {
        rig.now += 61_000
        rig.browse.instant = rig.browse.instant.plusSeconds(61)
    }

    private val livresTitles = recordedShelfEntities("personalized-livres", "recently-added").map { it.title }

    @Test
    fun `a library whose shelves the account may not open, or that is not there, is left out of the rows`() =
        runBlocking<Unit> {
            listOf(403, 404).forEach { code ->
                routes.fail(personalizedPath(BdId), code)
                aMinuteLater()

                val recent = row(HomeRowKind.RecentlyAdded)
                val upNext = rig.home.homeRow(HomeRowKind.UpNext, HomeRowExtent.Row)

                assertThat(recent.books.map { it.title }).containsExactlyElementsIn(livresTitles)
                assertThat(upNext).isInstanceOf(ApiResult.Success::class.java)
            }
        }

    @Test
    fun `a library removed on the server is no longer asked once the libraries are read again`() =
        runBlocking<Unit> {
            row(HomeRowKind.RecentlyAdded)
            routes.answer("/api/libraries", LivresAlone)
            routes.fail(personalizedPath(BdId), 404)
            aMinuteLater()

            val row = row(HomeRowKind.RecentlyAdded)

            assertThat(row.books.map { it.title }).containsExactlyElementsIn(livresTitles)
            assertThat(routes.requests(personalizedPath(BdId))).hasSize(1)
            assertThat(routes.requests("/api/libraries")).hasSize(2)
        }

    @Test
    fun `a library added on the server shows once the libraries are read again`() = runBlocking<Unit> {
        val newest = recordedShelfEntities("personalized-livres", "recently-added").first()
            .with("id", JsonPrimitive("novel-1"))
            .with("libraryId", JsonPrimitive("novels"))
            .with("addedAt", JsonPrimitive(Long.MAX_VALUE / 2))
        val novels = JsonArray(listOf(bookShelf("recently-added", listOf(newest))))
        routes.answer(personalizedPath("novels"), novels.toString())

        assertThat(row(HomeRowKind.RecentlyAdded).books.map { it.id.value }).doesNotContain("novel-1")
        routes.answer("/api/libraries", WithNovels)
        aMinuteLater()

        val books = row(HomeRowKind.RecentlyAdded).books
        assertThat(books.first().id.value).isEqualTo("novel-1")
    }

    @Test
    fun `each load of the rows reads the account's progress again, once`() = runBlocking<Unit> {
        val rows = listOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.RecentlyAdded)

        rows.forEach { row(it) }
        assertThat(routes.requests("/api/me")).hasSize(1)

        // A pull to refresh asks every row again, at once.
        rows.forEach { row(it) }
        assertThat(routes.requests("/api/me")).hasSize(2)

        // A See all screen's own load, then its pull to refresh.
        row(HomeRowKind.RecentlyAdded, HomeRowExtent.SeeAll)
        row(HomeRowKind.RecentlyAdded, HomeRowExtent.SeeAll)
        assertThat(routes.requests("/api/me")).hasSize(3)
    }
}
