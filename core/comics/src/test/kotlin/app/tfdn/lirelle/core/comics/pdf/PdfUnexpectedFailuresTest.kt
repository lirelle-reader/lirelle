package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.DocumentFailure
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.failed
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * What the system's renderer throws without saying so: a picture too large for the memory left, a runtime
 * exception of its own. It is a page that failed, or a document that cannot be opened, never the app's end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfUnexpectedFailuresTest {

    private val bench = PdfBench()
    private val documents = bench.documents

    @After
    fun tearDown() = bench.close()

    @Test
    fun `a drawing short of memory is a page that failed, and the drawings go on`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()
        documents.failing = setOf(1)
        documents.failure = { OutOfMemoryError("Failed to allocate a 67108880 byte allocation") }

        pages.show(1)

        assertThat(pages.failed(1).error).isEqualTo(ApiError.Unexpected)
        pages.show(2)
        pages.drawnAt(2, 2048)
        documents.failing = emptySet()
        pages.retry(1)
        pages.drawnAt(1, 2048)
    }

    @Test
    fun `a drawing the renderer gives up on is a page that failed`(): Unit = runBlocking {
        val pages = bench.open()
        pages.pageCount()
        documents.failing = setOf(0)
        documents.failure = { IllegalArgumentException("Unable to render the page") }

        pages.show(0)

        assertThat(pages.failed(0).error).isEqualTo(ApiError.Unexpected)
        pages.show(2)
        pages.drawnAt(2, 2048)
    }

    @Test
    fun `a sharper drawing that fails leaves the picture there is, and is not made again for that size`(): Unit =
        runBlocking {
            val pages = bench.open()
            pages.pageCount()
            pages.show(0)
            val small = pages.drawnAt(0, 2048)
            documents.failing = setOf(0)
            documents.failure = { OutOfMemoryError("Failed to allocate a 67108880 byte allocation") }

            pages.sharpen(0, 4000)
            awaitThat { Drawn(0, 2667, 4000) in documents.drawn }
            pages.sharpen(0, 4000)
            pages.sharpen(0, 4096)
            // The drawings are made in the order they were asked: once this one is there, those before were seen.
            pages.show(2)
            pages.drawnAt(2, 2048)

            assertThat(pages.page(0).first()).isEqualTo(small)
            assertThat(small.file.isFile).isTrue()
            assertThat(documents.drawn)
                .containsExactly(Drawn(0, 1365, 2048), Drawn(0, 2667, 4000), Drawn(2, 1365, 2048))
                .inOrder()

            // A smaller one may still fit.
            documents.failing = emptySet()
            pages.sharpen(0, 3000)
            pages.drawnAt(0, 3000)
        }

    @Test
    fun `a document the renderer gives up on is damaged, and leaves the cache`(): Unit = runBlocking {
        documents.refusal = { IllegalArgumentException("Unable to load the document") }
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Unreadable(DocumentFailure.Damaged))

        assertThat(bench.cache.held.get()).isEqualTo(0)
        assertThat(bench.cache.discarded).containsExactly(CachedBookKey(PdfFileId, "v1"))
        documents.refusal = null
        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(3))
        assertThat(pages.page(0).first()).isEqualTo(PageState.Loading)
    }
}
