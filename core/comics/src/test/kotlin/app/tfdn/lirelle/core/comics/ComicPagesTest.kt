package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * What a page becomes, and what is remembered of an album. With the platform's own decoder: the stand-in
 * takes anything for a picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicPagesTest {

    private val bench = ComicsBench()
    private val web = bench.web
    private val album = "$AlbumFileId-v1"

    @After
    fun tearDown() = bench.close()

    @Test
    fun `a page is a file of the album's folder, with the size of its picture`(): Unit = runBlocking {
        web.wide = setOf(1)
        val pages = bench.open()

        pages.show(0)
        pages.show(1)

        assertThat(pages.ready(0))
            .isEqualTo(PageState.Ready(File(bench.directory, "$album/0"), PortraitWidth, PortraitHeight))
        assertThat(pages.ready(1)).isEqualTo(PageState.Ready(File(bench.directory, "$album/1"), WideWidth, WideHeight))
        assertThat(pages.ready(0).file.length()).isEqualTo(PortraitPage.size)
        assertThat(bench.kept(album).filter { it.endsWith(".part") }).isEmpty()
    }

    @Test
    fun `a page nobody asked for is loading`(): Unit = runBlocking {
        val pages = bench.open()

        assertThat(pages.page(4).first()).isEqualTo(PageState.Loading)
    }

    @Test
    fun `a failure is that page's alone, and retry asks again`(): Unit = runBlocking {
        val pages = bench.open()
        web.failOnce(2)

        pages.show(2)
        assertThat(pages.failed(2).error).isEqualTo(ApiError.ServerFailed)
        pages.show(3)
        pages.ready(3)
        assertThat(pages.page(2).first()).isInstanceOf(PageState.Failed::class.java)
        assertThat(bench.kept(album).filter { it.endsWith(".part") }).isEmpty()

        pages.retry(2)

        pages.ready(2)
        assertThat(listOf(web.nextAsked(), web.nextAsked(), web.nextAsked())).containsExactly(2, 3, 2).inOrder()
    }

    @Test
    fun `a page past the end is not found`(): Unit = runBlocking {
        web.pageCount = 5
        val pages = bench.open()

        pages.show(5)

        assertThat(pages.failed(5).error).isEqualTo(ApiError.NotFound)
    }

    @Test
    fun `an answer that is no picture is a failure and is not kept`(): Unit = runBlocking {
        web.broken = setOf(0)
        val pages = bench.open()

        pages.show(0)

        assertThat(pages.failed(0).error).isEqualTo(ApiError.Unexpected)
        assertThat(bench.kept(album)).containsNoneOf("0", "0.part")
    }

    @Test
    fun `the pages on the device are not asked for again`(): Unit = runBlocking {
        val first = bench.open()
        first.show(0)
        first.settle(0)
        (0..5).forEach { first.ready(it) }
        first.close()
        repeat(6) { web.nextAsked() }

        val again = bench.open()
        // Without a word to the server, and before the reader says which page it shows.
        assertThat(again.ready(2).width).isEqualTo(PortraitWidth)
        again.show(1)
        again.settle(1)

        assertThat(web.nextAsked()).isEqualTo(6)
        assertThat(web.askedSoon()).isNull()
        assertThat(again.ready(1).file).isEqualTo(File(bench.directory, "$album/1"))
    }

    @Test
    fun `the page count is asked once`(): Unit = runBlocking {
        web.pageCount = 32
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(32))
        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(32))

        assertThat(web.countAsked.get()).isEqualTo(1)
    }

    @Test
    fun `an album opened again without the server has the page count it had`(): Unit = runBlocking {
        web.pageCount = 32
        bench.open().run {
            pageCount()
            close()
        }
        web.countFails = true

        assertThat(bench.open().pageCount()).isEqualTo(PageCountResult.Known(32))
    }

    @Test
    fun `the page count of an album never opened fails with the server, and is asked again`(): Unit = runBlocking {
        web.countFails = true
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Failed(ApiError.ServerFailed))
        web.countFails = false

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(20))
    }

    @Test
    fun `a page whose file went from the device is fetched again`(): Unit = runBlocking {
        val pages = bench.open()
        pages.show(0)
        pages.ready(0).file.delete()

        pages.show(0)

        assertThat(listOf(web.nextAsked(), web.nextAsked())).containsExactly(0, 0)
        assertThat(pages.ready(0).file.isFile).isTrue()
    }
}
