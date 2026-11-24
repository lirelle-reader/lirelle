package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.comics.pdf.awaitThat
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.offline.KeptPages
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val KeptCount = 6

/**
 * An album the downloads keep whole is read from its kept pages, where they are, and nothing is asked of the
 * server; one they do not keep whole is fetched as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeptAlbumPagesTest {

    private val bench = ComicsBench(limitBytes = 1)
    private val web = bench.web
    private val folder: File = Files.createTempDirectory("kept-pages").toFile()

    @After
    fun tearDown() {
        bench.close()
        folder.deleteRecursively()
    }

    private fun page(index: Int) = File(folder, index.toString())

    /** The downloads keep the album whole: [KeptCount] portrait pages, the last one as a DjVu's is served. */
    private fun keep() {
        repeat(KeptCount) { page(it).writeBytes(if (it == KeptCount - 1) WebpPage else PortraitPage) }
        bench.kept = KeptPages(KeptCount, ::page)
    }

    private fun portrait(index: Int) = PageState.Ready(page(index), PortraitWidth, PortraitHeight)

    @Test
    fun `a kept album is read with the server shut down, its count and its pages the kept ones`(): Unit =
        runBlocking {
            keep()
            web.stop()
            val pages = bench.open()

            assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(KeptCount))
            pages.show(0)

            assertThat(pages.ready(0)).isEqualTo(portrait(0))
            assertThat(pages.ready(KeptCount - 1)).isEqualTo(portrait(KeptCount - 1))
            assertThat(pages.opening.first()).isNull()
        }

    @Test
    fun `show, settle, jump, retry and sharpen ask nothing of the server`(): Unit = runBlocking {
        keep()
        // A count the kept one is not: the server's is never asked.
        web.pageCount = 20
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(KeptCount))
        pages.show(1)
        pages.settle(1)
        pages.jump(4)
        pages.retry(2)
        pages.sharpen(3, 4096)
        (0 until KeptCount).forEach { assertThat(pages.ready(it)).isEqualTo(portrait(it)) }

        assertThat(web.askedSoon()).isNull()
        assertThat(web.paths).isEmpty()
        assertThat(web.countAsked.get()).isEqualTo(0)
    }

    @Test
    fun `the kept pages are read where they are, and the reader's cache has nothing of them`(): Unit = runBlocking {
        keep()
        val pages = bench.open()

        pages.show(0)
        pages.ready(0)
        pages.show(5)
        pages.ready(5)

        // Not copied, so not counted in the cache's bound, here of one byte, and not its to drop.
        assertThat(bench.albums()).isEmpty()
        assertThat((0 until KeptCount).all { page(it).isFile }).isTrue()
    }

    @Test
    fun `a kept DjVu is read as a kept comic is`(): Unit = runBlocking {
        keep()
        val pages = bench.open(kind = PagedKind.Djvu)

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(KeptCount))
        pages.show(KeptCount - 1)

        assertThat(pages.ready(KeptCount - 1).file.readBytes()).isEqualTo(WebpPage)
        assertThat(web.paths).isEmpty()
    }

    @Test
    fun `a kept album is read whatever the version the server has of its file`(): Unit = runBlocking {
        keep()
        val pages = bench.open(version = "a newer one")

        pages.show(2)

        assertThat(pages.ready(2)).isEqualTo(portrait(2))
        assertThat(web.paths).isEmpty()
    }

    @Test
    fun `an album the downloads do not keep whole is fetched, and its book is not held`(): Unit = runBlocking {
        // Half fetched: its pages are there, and the downloads do not give them.
        repeat(2) { page(it).writeBytes(PortraitPage) }
        web.pageCount = 8
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(8))
        pages.show(1)

        assertThat(pages.ready(1).file).isEqualTo(File(bench.directory, "$AlbumFileId-v1/1"))
        assertThat(web.paths)
            .containsExactly("/comic/$AlbumFileId/pages", "/comic/$AlbumFileId/pages/1")
        assertThat(bench.keptAsked.get()).isEqualTo(1)
        assertThat(bench.bookHeld.get()).isEqualTo(0)
    }

    @Test
    fun `the book of a kept album is held while it is read, and given back when it is closed`(): Unit =
        runBlocking {
            keep()
            val pages = bench.open()
            pages.pageCount()
            pages.show(0)
            pages.ready(0)
            assertThat(bench.bookHeld.get()).isEqualTo(1)

            pages.close()

            awaitThat { bench.bookHeld.get() == 0 }
        }

    @Test
    fun `the book is given back when the reader's scope ends`(): Unit = runBlocking {
        keep()
        val pages = bench.open()
        pages.pageCount()
        assertThat(bench.bookHeld.get()).isEqualTo(1)

        bench.scope.cancel()

        awaitThat { bench.bookHeld.get() == 0 }
    }

    @Test
    fun `a kept page that is missing has failed, with no request, and retry reads it again`(): Unit = runBlocking {
        keep()
        val gone = page(2).readBytes()
        page(2).delete()
        val pages = bench.open()

        pages.show(2)
        assertThat(pages.failed(2).error).isEqualTo(ApiError.Unexpected)
        pages.show(3)
        assertThat(pages.ready(3)).isEqualTo(portrait(3))
        assertThat(web.paths).isEmpty()

        page(2).writeBytes(gone)
        pages.retry(2)

        assertThat(pages.ready(2)).isEqualTo(portrait(2))
        assertThat(web.paths).isEmpty()
    }

    @Test
    fun `a kept page that is no picture has failed, and the server is not asked for it`(): Unit = runBlocking {
        keep()
        page(1).writeText("not a picture")
        val pages = bench.open()

        pages.show(1)

        assertThat(pages.failed(1).error).isEqualTo(ApiError.Unexpected)
        assertThat(web.askedSoon()).isNull()
        assertThat(page(1).readText()).isEqualTo("not a picture")
    }

    @Test
    fun `a page past the end of a kept album is not found`(): Unit = runBlocking {
        keep()
        val pages = bench.open()

        pages.show(KeptCount)

        assertThat(pages.failed(KeptCount).error).isEqualTo(ApiError.NotFound)
        assertThat(web.paths).isEmpty()
    }
}
