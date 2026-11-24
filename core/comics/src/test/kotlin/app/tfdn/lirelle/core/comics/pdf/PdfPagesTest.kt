package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.failed
import app.tfdn.lirelle.core.comics.fetch.ImageSize
import app.tfdn.lirelle.core.comics.fetch.imageSize
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

/** What a page of a PDF becomes: a picture drawn on the device, kept with the pages fetched. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfPagesTest {

    private val bench = PdfBench()
    private val documents = bench.documents
    private val album = "$PdfFileId-v1"

    @After
    fun tearDown() = bench.close()

    @Test
    fun `a page is drawn for a long side of 2048 pixels, into a picture of the album's folder`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()

        pages.show(0)

        val page = pages.drawnAt(0, 2048)
        assertThat(page).isEqualTo(PageState.Ready(File(bench.directory, "$album/0-2048"), 1365, 2048))
        assertThat(imageSize(page.file)).isEqualTo(ImageSize(1365, 2048))
        assertThat(documents.drawn).containsExactly(Drawn(0, 1365, 2048))
        assertThat(bench.kept(album).filter { it.endsWith(".part") }).isEmpty()
    }

    @Test
    fun `a page wider than high has its long side across`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()

        pages.show(1)

        val page = pages.drawnAt(1, 2048)
        assertThat(page.width to page.height).isEqualTo(2048 to 1024)
    }

    @Test
    fun `a page nobody asked for is loading`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()

        assertThat(pages.page(2).first()).isEqualTo(PageState.Loading)
        assertThat(documents.drawnSoon()).isNull()
    }

    @Test
    fun `a page asked before the document is open is drawn once it is`(): Unit = runBlocking {
        val pages = bench.open()
        pages.show(0)

        pages.pageCount()

        assertThat(pages.drawnAt(0, 2048).height).isEqualTo(2048)
    }

    @Test
    fun `a page past the end is not found`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()

        pages.show(3)

        assertThat(pages.failed(3).error).isEqualTo(ApiError.NotFound)
        assertThat(documents.drawn).isEmpty()
    }

    @Test
    fun `a drawing that fails is that page's alone, and retry draws again`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()
        documents.failing = setOf(1)

        pages.show(1)
        assertThat(pages.failed(1).error).isEqualTo(ApiError.Unexpected)
        pages.show(2)
        pages.drawnAt(2, 2048)
        assertThat(pages.page(1).first()).isInstanceOf(PageState.Failed::class.java)
        documents.failing = emptySet()

        pages.retry(1)

        pages.drawnAt(1, 2048)
        assertThat(documents.drawn.map { it.index }).containsExactly(1, 2, 1).inOrder()
        assertThat(bench.kept(album).filter { it.endsWith(".part") }).isEmpty()
    }

    @Test
    fun `a page drawn is not drawn again, in this opening or the next`(): Unit = runBlocking {
        val first = bench.open()
        first.pageCount()
        first.show(0)
        first.drawnAt(0, 2048)
        first.show(0)
        first.close()

        val again = bench.open()
        again.pageCount()
        // Before the reader says which page it shows.
        assertThat(again.drawnAt(0, 2048).file).isEqualTo(File(bench.directory, "$album/0-2048"))
        again.show(0)
        again.show(1)
        again.drawnAt(1, 2048)

        assertThat(documents.drawn.map { it.index }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `another version of the file is drawn afresh, and the pages of the old one go`(): Unit = runBlocking {
        bench.open().run {
            pageCount()
            show(0)
            drawnAt(0, 2048)
            close()
        }

        val newer = bench.open(version = "v2")
        newer.pageCount()
        newer.show(0)

        assertThat(newer.drawnAt(0, 2048).file).isEqualTo(File(bench.directory, "$PdfFileId-v2/0-2048"))
        assertThat(documents.drawn).hasSize(2)
        assertThat(bench.albums()).containsExactly("$PdfFileId-v2")
    }

    @Test
    fun `a page whose picture went from the device is drawn again`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()
        pages.show(0)
        pages.drawnAt(0, 2048).file.delete()

        pages.show(0)

        awaitThat { documents.drawn.size == 2 }
        awaitThat { File(bench.directory, "$album/0-2048").isFile }
    }

    @Test
    fun `a zoomed page is drawn again larger, and its smaller picture goes`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()
        pages.show(0)
        val small = pages.drawnAt(0, 2048)

        pages.sharpen(0, 3000)

        val large = pages.drawnAt(0, 3000)
        assertThat(large).isEqualTo(PageState.Ready(File(bench.directory, "$album/0-3000"), 2000, 3000))
        assertThat(imageSize(large.file)?.height).isEqualTo(3000)
        awaitThat { !small.file.exists() }
        assertThat(bench.kept(album)).contains("0-3000")
        assertThat(bench.kept(album)).doesNotContain("0-2048")
    }

    @Test
    fun `a page is not drawn again for a size it has, nor for a smaller one, and never past 4096`(): Unit =
        runBlocking {
            val pages = bench.open()
            pages.pageCount()
            pages.show(0)
            pages.drawnAt(0, 2048)
            pages.sharpen(0, 3000)
            pages.drawnAt(0, 3000)

            pages.sharpen(0, 3000)
            pages.sharpen(0, 2500)
            pages.sharpen(0, 1000)
            // The drawings are made in the order they were asked: once this one is there, those before were seen.
            pages.sharpen(1, 9000)
            val bounded = pages.drawnAt(1, 4096)
            pages.sharpen(1, 5000)
            pages.sharpen(2, 2048)
            pages.drawnAt(2, 2048)

            assertThat(bounded.width to bounded.height).isEqualTo(4096 to 2048)
            assertThat(documents.drawn).containsExactly(
                Drawn(0, 1365, 2048),
                Drawn(0, 2000, 3000),
                Drawn(1, 4096, 2048),
                Drawn(2, 1365, 2048),
            ).inOrder()
        }

    @Test
    fun `a page kept larger is found again larger on reopening`(): Unit = runBlocking {
        bench.open().run {
            pageCount()
            show(0)
            drawnAt(0, 2048)
            sharpen(0, 3000)
            drawnAt(0, 3000)
            close()
        }

        val again = bench.open()
        again.pageCount()
        again.show(0)

        assertThat(again.drawnAt(0, 3000).file.name).isEqualTo("0-3000")
        assertThat(documents.drawn).hasSize(2)
    }
}
