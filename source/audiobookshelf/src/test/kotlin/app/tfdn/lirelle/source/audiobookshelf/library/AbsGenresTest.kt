package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedAccount
import app.tfdn.lirelle.source.audiobookshelf.testing.bdId
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.livresId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val PodcastsId = "2c073bf4-c0cb-4380-b775-efdbf060af02"

/** The genres: each book library's, from its filter data, joined, each name once, as a reader sorts them. */
class AbsGenresTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var genres: AbsGenres

    @Before
    fun setUp() {
        server.start()
        answer(RecordedOn.Latest)
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** The account recorded on [on], Livres' filter data, and BD's with a few genres of its own. */
    private fun answer(on: RecordedOn) {
        routes = LibraryRoutes(server)
        answerRecordedAccount(routes, on)
        routes.answer(libraryPath(livresId(on)), fixtureBody("filterdata-livres", on))
        routes.answer(
            libraryPath(bdId(on)),
            """{"filterdata": {"genres": [" Humour ", "Bande dessinée", "", "aventure", "Écologie"]}}""",
        )
        val rig = BrowseRig(server)
        genres = AbsGenres(rig.profileServer, rig.whole)
    }

    private suspend fun names(): List<String> = (genres.genres() as ApiResult.Success).value.map { it.name }

    @Test
    fun `the genres of each book library are joined, trimmed, once each, as a reader sorts them`() =
        runBlocking<Unit> {
            val all = (genres.genres() as ApiResult.Success).value

            val names = all.map { it.name }
            // Two names a reader sorts alike keep the order they came in: Livres' before BD's.
            assertThat(names)
                .containsAtLeast("Arts", "Aventure", "aventure", "Bande dessinée", "Écologie", "Essai", "Voyage")
                .inOrder()
            assertThat(names.count { it == "Humour" }).isEqualTo(1)
            assertThat(names).doesNotContain("")
            assertThat(names).hasSize(26)
            assertThat(all.map { it.bookCount }.toSet()).containsExactly(null)
            for (library in listOf(livresId(RecordedOn.Latest), bdId(RecordedOn.Latest))) {
                assertThat(routes.requests(libraryPath(library)).single().url.queryParameter("include"))
                    .isEqualTo("filterdata")
            }
            assertThat(routes.requests(libraryPath(PodcastsId))).isEmpty()
        }

    @Test
    fun `a library whose filter data fails fails the genres`() = runBlocking<Unit> {
        routes.fail(libraryPath(bdId(RecordedOn.Latest)), 500)

        assertThat(genres.genres()).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `2_26_3's filter data is read`() = runBlocking<Unit> {
        answer(RecordedOn.Minimum)

        assertThat(names()).contains("Science-fiction")
        assertThat((genres.genres() as ApiResult.Success).value.first()).isEqualTo(Genre("Arts", bookCount = null))
    }

    private fun libraryPath(libraryId: String) = "/api/libraries/$libraryId"
}
