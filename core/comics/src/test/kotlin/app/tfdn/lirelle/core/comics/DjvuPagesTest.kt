package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A DjVu is read as a comic is, its pages asked of the source as a DjVu's: pictures the server draws. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DjvuPagesTest {

    private val bench = ComicsBench()
    private val web = bench.web
    private val album = "$AlbumFileId-v1"
    private val djvu = "/djvu/$AlbumFileId"

    @After
    fun tearDown() = bench.close()

    private fun open(): ComicPages {
        web.webp = true
        return bench.open(kind = PagedKind.Djvu)
    }

    @Test
    fun `the page count is asked as a DjVu's`(): Unit = runBlocking {
        web.pageCount = 8
        val pages = open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(8))

        assertThat(web.paths).containsExactly("$djvu/pages")
    }

    @Test
    fun `a page is the server's picture, kept in the folder of its file and version`(): Unit = runBlocking {
        val pages = open()

        pages.show(3)

        assertThat(pages.ready(3))
            .isEqualTo(PageState.Ready(File(bench.directory, "$album/3"), PortraitWidth, PortraitHeight))
        assertThat(pages.ready(3).file.readBytes()).isEqualTo(WebpPage)
        assertThat(web.paths).containsExactly("$djvu/pages/3")
    }

    @Test
    fun `the pages around the reader are asked as a DjVu's, in the window's order`(): Unit = runBlocking {
        web.pageCount = 8
        val pages = open()
        pages.pageCount()

        pages.show(2)
        pages.ready(2)
        pages.settle(2)
        (0..7).forEach { pages.ready(it) }

        val asked = List(8) { web.nextAsked() }
        assertThat(asked.first()).isEqualTo(2)
        assertThat(asked).containsExactly(2, 3, 4, 1, 5, 0, 6, 7)
        assertThat(web.paths.drop(1)).containsExactlyElementsIn(asked.map { "$djvu/pages/$it" })
        assertThat(web.askedSoon()).isNull()
    }

    @Test
    fun `a failure is that page's alone, and retry asks again as a DjVu's`(): Unit = runBlocking {
        val pages = open()
        web.failOnce(1)

        pages.show(1)
        assertThat(pages.failed(1).error).isEqualTo(ApiError.ServerFailed)
        pages.retry(1)

        pages.ready(1)
        assertThat(web.paths).containsExactly("$djvu/pages/1", "$djvu/pages/1")
    }

    @Test
    fun `a page past the end is not found`(): Unit = runBlocking {
        web.pageCount = 8
        val pages = open()

        pages.show(8)

        assertThat(pages.failed(8).error).isEqualTo(ApiError.NotFound)
        assertThat(web.paths).containsExactly("$djvu/pages/8")
    }

    @Test
    fun `a page count that fails is the server's failure`(): Unit = runBlocking {
        web.countFails = true

        assertThat(open().pageCount()).isEqualTo(PageCountResult.Failed(ApiError.ServerFailed))
    }

    @Test
    fun `a PDF is handed to the opener of documents, and the server's pages are not asked`() {
        val key = ComicKey(AlbumFileId, "v1", PagedKind.Pdf, bookId = BookId("3"))

        val pages = bench.opener.open(key, bench.scope)

        assertThat(pages).isSameInstanceAs(PdfAsked)
        assertThat(bench.pdfAsked).containsExactly(key)
        assertThat(web.paths).isEmpty()
    }

    @Test
    fun `a comic is still asked as a comic's`(): Unit = runBlocking {
        val pages = bench.open()

        pages.pageCount()
        pages.show(0)
        pages.ready(0)

        val comic = "/comic/$AlbumFileId"
        assertThat(web.paths).containsExactly("$comic/pages", "$comic/pages/0").inOrder()
    }
}
