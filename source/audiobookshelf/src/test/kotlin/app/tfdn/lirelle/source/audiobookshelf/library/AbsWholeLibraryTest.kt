package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.testing.BdId
import app.tfdn.lirelle.source.audiobookshelf.testing.BrowseRig
import app.tfdn.lirelle.source.audiobookshelf.testing.LibraryRoutes
import app.tfdn.lirelle.source.audiobookshelf.testing.LivresId
import app.tfdn.lirelle.source.audiobookshelf.testing.answerRecordedLibrary
import app.tfdn.lirelle.source.audiobookshelf.testing.itemsPath
import app.tfdn.lirelle.source.audiobookshelf.testing.pagedItems
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Livres alone, as a server that lost BD would answer. */
private const val LivresAlone = """{"libraries":[
    {"id":"$LivresId","name":"Livres","displayOrder":1,"mediaType":"book"}]}"""

/** Livres, and a library of books with a book's covers added since. */
private const val LivresAndNovels = """{"libraries":[
    {"id":"$LivresId","name":"Livres","displayOrder":1,"mediaType":"book"},
    {"id":"novels","name":"Romans","displayOrder":2,"mediaType":"book"}]}"""

/**
 * What the profile keeps of its server: the libraries for a minute, replaced by the selector's read; each library's
 * whole list until it is said stale; the libraries never waiting for a whole list, nor one library for another.
 */
class AbsWholeLibraryTest {

    private val server = MockWebServer()
    private lateinit var routes: LibraryRoutes
    private lateinit var rig: BrowseRig

    @Before
    fun setUp() {
        server.start()
        routes = LibraryRoutes(server)
        answerRecordedLibrary(routes)
        rig = BrowseRig(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private suspend fun libraries(): List<String> =
        rig.whole.libraries(rig.profileServer.remote(), refresh = null).map { it.name }

    @Test
    fun `the libraries are kept a minute, then read again`() = runBlocking<Unit> {
        assertThat(libraries()).containsExactly("Livres", "BD").inOrder()
        routes.answer("/api/libraries", LivresAlone)

        rig.instant = rig.instant.plusSeconds(60)
        assertThat(libraries()).containsExactly("Livres", "BD").inOrder()

        rig.instant = rig.instant.plusSeconds(1)
        assertThat(libraries()).containsExactly("Livres")
        assertThat(routes.requests("/api/libraries")).hasSize(2)
    }

    @Test
    fun `the selector's read of the libraries replaces those kept`() = runBlocking<Unit> {
        assertThat(libraries()).containsExactly("Livres", "BD").inOrder()
        routes.answer("/api/libraries", LivresAndNovels)

        val selector = rig.profileServer.libraries(rig.whole) as ApiResult.Success

        assertThat(selector.value.map { it.name }).containsExactly("Livres", "Romans").inOrder()
        assertThat(libraries()).containsExactly("Livres", "Romans").inOrder()
        assertThat(routes.requests("/api/libraries")).hasSize(2)
    }

    @Test
    fun `a whole list is kept until a refresh says it stale, then read again once`() = runBlocking<Unit> {
        val remote = rig.profileServer.remote()

        rig.whole.items(remote, LivresId, refresh = null)
        rig.whole.items(remote, LivresId, refresh = null)
        assertThat(routes.requests(itemsPath(LivresId))).hasSize(1)

        val refresh = AbsWholeLibrary.Refresh()
        rig.whole.markStale(LivresId, refresh)
        rig.whole.items(remote, LivresId, refresh = null)
        rig.whole.items(remote, LivresId, refresh = null)
        assertThat(routes.requests(itemsPath(LivresId))).hasSize(2)

        // Once for a refresh, whose every page says it.
        rig.whole.markStale(LivresId, refresh)
        rig.whole.items(remote, LivresId, refresh = null)
        assertThat(routes.requests(itemsPath(LivresId))).hasSize(2)

        // Another library's list is its own.
        rig.whole.markStale(BdId, AbsWholeLibrary.Refresh())
        rig.whole.items(remote, LivresId, refresh = null)
        assertThat(routes.requests(itemsPath(LivresId))).hasSize(2)
    }

    @Test
    fun `while a whole list is read, the libraries, the covers and another library's list do not wait`() =
        runBlocking<Unit> {
            val release = CountDownLatch(1)
            val livres = pagedItems("items-livres-all")
            routes.answer(itemsPath(LivresId)) { request ->
                release.await(10, TimeUnit.SECONDS)
                livres(request)
            }
            val remote = rig.profileServer.remote()

            val slow = async(Dispatchers.IO) { rig.whole.items(remote, LivresId, refresh = null) }
            withTimeout(5_000) { while (routes.requests(itemsPath(LivresId)).isEmpty()) delay(10) }

            withContext(Dispatchers.IO) {
                withTimeout(3_000) {
                    assertThat(libraries()).containsExactly("Livres", "BD").inOrder()
                    assertThat(rig.whole.items(remote, BdId, refresh = null)).isNotEmpty()
                }
            }
            assertThat(slow.isCompleted).isFalse()

            release.countDown()
            assertThat(slow.await()).hasSize(40)
        }
}
