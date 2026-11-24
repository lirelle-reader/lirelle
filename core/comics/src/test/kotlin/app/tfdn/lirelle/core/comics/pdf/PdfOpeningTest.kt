package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.DocumentFailure
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val WaitMillis = 5_000L

/** From a PDF's key to a document open: the whole file through the cache, then the renderer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfOpeningTest {

    private val bench = PdfBench()
    private val key = CachedBookKey(PdfFileId, "v1")

    @After
    fun tearDown() = bench.close()

    @Test
    fun `the document is fetched whole through the cache, and its pages counted`(): Unit = runBlocking {
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(3))

        assertThat(bench.server.downloads).containsExactly(PdfFileId to false)
        assertThat(bench.documents.opened).containsExactly(bench.cache.cached(key))
        assertThat(bench.documents.opened.single().readBytes()).isEqualTo(fixture("three-pages.pdf"))
    }

    @Test
    fun `the document is fetched and opened once, however often the count is asked`(): Unit = runBlocking {
        val pages = bench.open()

        pages.pageCount()
        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(3))

        assertThat(bench.server.downloads).hasSize(1)
        assertThat(bench.documents.opened).hasSize(1)
    }

    @Test
    fun `the opening says what came of the document while it is fetched, and nothing once it is there`(): Unit =
        runBlocking {
            val total = fixture("three-pages.pdf").size.toLong()
            bench.server.downloadGate = CompletableDeferred()
            val pages = bench.open()
            assertThat(pages.opening.first()).isNull()

            val count = async { pages.pageCount() }
            val half = withTimeout(WaitMillis) { pages.opening.first { it != null && it.receivedBytes > 0 } }
            assertThat(half).isEqualTo(OpeningProgress(total / 2, total))
            bench.server.downloadGate?.complete(Unit)

            assertThat(count.await()).isEqualTo(PageCountResult.Known(3))
            assertThat(pages.opening.first()).isNull()
        }

    @Test
    fun `a document in the cache is opened without the server`(): Unit = runBlocking {
        bench.open().run {
            pageCount()
            close()
        }
        bench.server.downloadResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(bench.open().pageCount()).isEqualTo(PageCountResult.Known(3))
        assertThat(bench.server.downloads).hasSize(1)
    }

    @Test
    fun `a fetch that fails is the network's failure, and the count asked again fetches again`(): Unit = runBlocking {
        bench.server.downloadResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Failed(ApiError.ServerUnreachable))
        assertThat(pages.opening.first()).isNull()
        assertThat(bench.cache.held.get()).isEqualTo(0)
        bench.server.downloadResult = ApiResult.Success(Unit)

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(3))
    }

    @Test
    fun `a protected document says so, and is not fetched again for it`(): Unit = runBlocking {
        bench.server.fileContent = fixture("protected.pdf")
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Unreadable(DocumentFailure.Protected))

        assertThat(bench.cache.held.get()).isEqualTo(0)
        assertThat(bench.cache.discarded).isEmpty()
        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Unreadable(DocumentFailure.Protected))
        assertThat(bench.server.downloads).hasSize(1)
    }

    @Test
    fun `a document cut short is damaged, and leaves the cache for a whole one to be fetched`(): Unit = runBlocking {
        val whole = fixture("three-pages.pdf")
        bench.server.fileContent = whole.copyOf(whole.size / 2)
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Unreadable(DocumentFailure.Damaged))

        assertThat(bench.cache.held.get()).isEqualTo(0)
        assertThat(bench.cache.discarded).containsExactly(key)
        assertThat(bench.cache.cached(key)).isNull()
        bench.server.fileContent = whole
        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(3))
    }

    @Test
    fun `a document without its book cannot be fetched`(): Unit = runBlocking {
        val pages = bench.open(bookId = null)

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Failed(ApiError.Unexpected))
        assertThat(bench.server.downloads).isEmpty()
    }

    @Test
    fun `the copy the downloads keep is opened as it is, with no network, whatever its version`(): Unit =
        runBlocking {
            val copy = File.createTempFile("kept", ".pdf").apply { writeBytes(fixture("three-pages.pdf")) }
            bench.kept = copy
            bench.server.downloadResult = ApiResult.Failure(ApiError.ServerUnreachable)
            val pages = bench.open(version = "what the server has now")

            assertThat(pages.pageCount()).isEqualTo(PageCountResult.Known(3))

            assertThat(bench.documents.opened).containsExactly(copy)
            assertThat(bench.server.downloads).isEmpty()
            assertThat(bench.cache.cached(CachedBookKey(PdfFileId, "what the server has now"))).isNull()
            copy.delete()
        }

    @Test
    fun `the kept book is in use while its document is open, and no longer once the reader left`(): Unit =
        runBlocking {
            val copy = File.createTempFile("kept", ".pdf").apply { writeBytes(fixture("three-pages.pdf")) }
            bench.kept = copy
            val pages = bench.open()

            pages.pageCount()
            assertThat(bench.bookHeld.get()).isEqualTo(1)
            pages.close()

            awaitThat { bench.documents.closed.get() == 1 && bench.bookHeld.get() == 0 }
            copy.delete()
        }

    @Test
    fun `a kept copy that is no document is said damaged, and is left where it is`(): Unit = runBlocking {
        val whole = fixture("three-pages.pdf")
        val copy = File.createTempFile("kept", ".pdf").apply { writeBytes(whole.copyOf(whole.size / 2)) }
        bench.kept = copy
        val pages = bench.open()

        assertThat(pages.pageCount()).isEqualTo(PageCountResult.Unreadable(DocumentFailure.KeptDamaged))

        assertThat(copy.isFile).isTrue()
        assertThat(bench.bookHeld.get()).isEqualTo(0)
        assertThat(bench.server.downloads).isEmpty()
        copy.delete()
    }

    @Test
    fun `the document is held while it is open, and closing gives it back and closes the renderer`(): Unit =
        runBlocking {
            val pages = bench.open()
            pages.pageCount()
            pages.show(0)
            pages.drawnAt(0, FirstLongSidePx)
            assertThat(bench.cache.held.get()).isEqualTo(1)
            assertThat(bench.documents.closed.get()).isEqualTo(0)

            pages.close()

            awaitThat { bench.documents.closed.get() == 1 && bench.cache.held.get() == 0 }
            pages.close()
            assertThat(bench.documents.closed.get()).isEqualTo(1)
        }

    @Test
    fun `a document that opens after the reader left is closed at once`(): Unit = runBlocking {
        bench.server.downloadGate = CompletableDeferred()
        val pages = bench.open()
        val count = async { pages.pageCount() }
        withTimeout(WaitMillis) { pages.opening.first { it != null && it.receivedBytes > 0 } }

        pages.close()
        bench.server.downloadGate?.complete(Unit)
        count.await()

        awaitThat { bench.documents.closed.get() == bench.documents.opened.size && bench.cache.held.get() == 0 }
    }
}
