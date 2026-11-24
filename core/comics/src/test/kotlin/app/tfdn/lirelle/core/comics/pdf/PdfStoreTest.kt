package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The pages drawn are kept as the pages fetched are: under the same bound, held while they are read. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfStoreTest {

    /** No room at all: only what a reader has open stays. */
    private val bench = PdfBench(limitBytes = 0)

    @After
    fun tearDown() = bench.close()

    @Test
    fun `the pages of a document being read stay above the bound, and go once another is opened`(): Unit =
        runBlocking {
            bench.time = 10_000
            val first = bench.open(fileId = FileId("1"))
            first.pageCount()
            first.show(0)
            first.drawnAt(0, FirstLongSidePx)
            bench.store.trim()
            assertThat(bench.kept("1-v1")).contains("0-2048")
            first.close()

            bench.time = 20_000
            val second = bench.open(fileId = FileId("2"))
            second.pageCount()
            second.show(0)
            second.drawnAt(0, FirstLongSidePx)

            assertThat(bench.albums()).containsExactly("2-v1")
        }

    @Test
    fun `the pages are given back to the store when the scope of the reader ends`(): Unit = runBlocking {
        val pages = bench.open(fileId = FileId("1"))
        pages.pageCount()
        pages.show(0)
        pages.drawnAt(0, FirstLongSidePx)

        bench.scope.coroutineContext.job.cancelAndJoin()

        awaitThat { bench.documents.closed.get() == 1 && bench.cache.held.get() == 0 }
        bench.store.trim()
        assertThat(bench.albums()).isEmpty()
    }
}
