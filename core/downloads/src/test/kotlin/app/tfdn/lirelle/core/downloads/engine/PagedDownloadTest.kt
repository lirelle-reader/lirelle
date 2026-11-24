package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.offline.ReaderCachedPages
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val Pages = 4

/** The pages of an album come from a server through the real downloader, into a real folder. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    private val bookOne = BookId("1")

    /** A picture per page, each of its own size: 100, 200, 300 and 400 bytes. */
    private val pictures = List(Pages) { jpeg(100 * (it + 1), seed = it) }
    private val whole = pictures.sumOf { it.size }.toLong()
    private val paths = List(Pages) { "/pages/comic/$AlbumFile/$it" }

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun httpTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        val fixture = QueueFixture(this, folder.newFolder("downloads"), fetch = { places, io ->
            val locations = testLocations(server.url("/").toString().trimEnd('/'))
            Downloader(OkHttpClient(), places, locations, TestRefusals(), io)
        })
        fixture.plans.answers[bookOne to Text] = ApiResult.Success(album(Pages))
        fixture.test(this)
    }

    private fun serve(onAsked: (Int) -> Unit = {}, page: (Int) -> ByteArray = { pictures[it] }): ServedAlbum =
        ServedAlbum(page, onAsked).also { server.dispatcher = it }

    private fun QueueFixture.page(index: Int): File = file(bookOne, Text, index.toString())

    @Test
    fun `the pages are asked one at a time and in order, each kept under its index`() = httpTest {
        val album = serve()
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(album.asked).containsExactlyElementsIn(paths).inOrder()
        assertThat(album.atOnce).isEqualTo(1)
        assertThat(keptPages(bookOne)).containsExactly("0", "1", "2", "3").inOrder()
        pictures.forEachIndexed { index, picture -> assertThat(page(index).readBytes()).isEqualTo(picture) }
        assertThat(page(0).parentFile).isEqualTo(File(files.bookDir(bookOne), "pages"))
    }

    @Test
    fun `the record says how many pages the device has as each one arrives`() = httpTest {
        val kept = mutableListOf<PageCount?>()
        serve(onAsked = { kept += runBlocking { text(bookOne).pages } })
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(kept).containsExactly(
            PageCount(0, Pages), PageCount(1, Pages), PageCount(2, Pages), PageCount(3, Pages),
        ).inOrder()
        assertThat(text(bookOne).pages).isEqualTo(PageCount(Pages, Pages))
        assertThat(text(bookOne).keptPages).isEqualTo(Pages)
    }

    @Test
    fun `the size is the file's until the album is whole, then what its pages take`() = httpTest {
        serve()
        network.value = NoNetwork
        queue.ask(bookOne, setOf(Text), mapOf(Text to 5_500))
        runner.runUntilIdle()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 0, 5_500))

        network.value = Wifi
        runner.runUntilIdle()

        val going = registry.states.map { it.second }.filterIsInstance<FormatState.Downloading>()
        // Until the last page is there the total is the estimate; with them all it is what they take.
        assertThat(going.dropLast(1).map { it.totalBytes }.distinct()).containsExactly(AlbumBytes)
        assertThat(going.map { it.receivedBytes }).isInOrder()
        assertThat(going.last()).isEqualTo(FormatState.Downloading(whole, whole))
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(whole))
        assertThat(checkNotNull(registry.book(bookOne)).bytesOnDevice).isEqualTo(whole)
    }

    @Test
    fun `the record keeps the file's size and the name of its text`() = httpTest {
        serve()
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(text(bookOne).expectedBytes).isEqualTo(AlbumBytes)
        assertThat(text(bookOne).textName).isEqualTo(TextName.Cbz)
        assertThat(text(bookOne).fileId).isEqualTo(AlbumFile)
    }

    @Test
    fun `a download cut goes on from the page it had reached, the pages kept not asked again`() = httpTest {
        val album = serve()
        album.cuts += 2
        queue.ask(bookOne, setOf(Text))
        keepRunning()
        it.runCurrent()

        assertThat(album.asked).containsExactlyElementsIn(paths.take(3)).inOrder()
        // What came of the page cut is on the device until the page is asked again.
        val received = 300 + files.part(bookOne, Text, "2").length()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, received, AlbumBytes))
        assertThat(text(bookOne).pages).isEqualTo(PageCount(2, Pages))
        val entry = queue.queue.first().waiting.single()
        assertThat(entry.pages).isEqualTo(PageCount(2, Pages))
        assertThat(entry.textName).isEqualTo(TextName.Cbz)
        assertThat(entry.totalBytes).isEqualTo(AlbumBytes)
        assertThat(entry.receivedBytes).isEqualTo(received)

        it.advanceTimeBy(5_000)
        it.runCurrent()

        assertThat(album.asked).containsExactlyElementsIn(paths.take(3) + paths.drop(2)).inOrder()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(whole))
        assertThat(text(bookOne).pages).isEqualTo(PageCount(Pages, Pages))
        assertThat(keptPages(bookOne)).containsExactly("0", "1", "2", "3").inOrder()
    }

    @Test
    fun `a download that goes on counts the pages that are really there`() = httpTest {
        val album = serve()
        album.cuts += 2
        queue.ask(bookOne, setOf(Text))
        keepRunning()
        it.runCurrent()
        // What the register said last is wrong: a page went, and the count was written before another came.
        page(0).delete()
        registry.update(bookOne) { book ->
            book?.copy(formats = book.formats.map { record -> record.copy(keptPages = 3) })
        }
        album.asked.clear()

        it.advanceTimeBy(5_000)
        it.runCurrent()

        assertThat(album.asked).containsExactly(paths[0], paths[2], paths[3]).inOrder()
        assertThat(text(bookOne).pages).isEqualTo(PageCount(Pages, Pages))
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(whole))
    }

    @Test
    fun `a page the page reader has seen, of the same file and version, is copied and not asked`() = httpTest {
        val album = serve()
        val served = FakeServedPages()
        served.seen[Triple(AlbumFile, "v1", 1)] = folder.newFile("seen-1").apply { writeBytes(pictures[1]) }
        // Of another version of the file: not the page planned.
        served.seen[Triple(AlbumFile, "v0", 2)] = folder.newFile("seen-2").apply { writeBytes(jpeg(50)) }
        caches += ReaderCachedPages(served)
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(album.asked).containsExactly(paths[0], paths[2], paths[3]).inOrder()
        assertThat(page(1).readBytes()).isEqualTo(pictures[1])
        assertThat(page(2).readBytes()).isEqualTo(pictures[2])
        assertThat(served.seen.getValue(Triple(AlbumFile, "v1", 1)).readBytes()).isEqualTo(pictures[1])
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(whole))
        assertThat(text(bookOne).pages).isEqualTo(PageCount(Pages, Pages))
        assertThat(keptPages(bookOne)).containsExactly("0", "1", "2", "3").inOrder()
    }

    @Test
    fun `a page that is no picture fails the download as a bad file does, the pages before it kept`() = httpTest {
        val album = serve { index -> if (index == 2) """{"not":"a picture"}""".toByteArray() else pictures[index] }
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Failed(DownloadFailure.NotAnEpub, 300))
        assertThat(album.asked).containsExactlyElementsIn(paths.take(3)).inOrder()
        assertThat(keptPages(bookOne)).containsExactly("0", "1")
        assertThat(text(bookOne).pages).isEqualTo(PageCount(2, Pages))
    }

    @Test
    fun `the room is checked against the file's size before a page is asked`() = httpTest {
        val album = serve()
        freeBytes = 1_000
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(state(bookOne, Text))
            .isEqualTo(FormatState.Failed(DownloadFailure.NoSpace(AlbumBytes - 1_000), 0))
        assertThat(album.asked).isEmpty()
    }

    @Test
    fun `an account that may not download keeps no page`() = httpTest {
        val album = serve()
        canDownload.value = false

        assertThat(queue.ask(bookOne, setOf(Text))).isEqualTo(AskRefusal.NotAllowed)
        runner.runUntilIdle()

        assertThat(registry.book(bookOne)).isNull()
        assertThat(album.asked).isEmpty()
    }

    @Test
    fun `the register writes a downloaded album as its count, with no entry per page`() = httpTest {
        serve()
        queue.ask(bookOne, setOf(Text))

        runner.runUntilIdle()

        assertThat(written(bookOne)).contains(""""pages":{"count":$Pages,"fileId":"$AlbumFile","paged":"Comic"}""")
        assertThat(written(bookOne)).contains(""""files":[],""")
        assertThat(written(bookOne)).doesNotContain(""""source"""")
        assertThat(written(bookOne)).doesNotContain("http")
    }
}
