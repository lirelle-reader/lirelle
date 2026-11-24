package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.fetch.ImageSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Which pages are drawn, in which order, one at a time: every drawing is held until the test lets it go. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfPagesOrderTest {

    private val bench = PdfBench()
    private val documents = bench.documents
    private lateinit var pages: ComicPages

    @Before
    fun setUp() = runBlocking {
        // Small pages: what is looked at here is the order, and a drawing is a picture to make.
        documents.sizes = List(50) { ImageSize(30, 45) }
        documents.holding = true
        pages = bench.open()
        pages.pageCount()
        Unit
    }

    @After
    fun tearDown() {
        pages.close()
        bench.close()
    }

    /**
     * Lets the drawings go one at a time and gives the order they began in: [total] are expected, and one more
     * is listed if it comes.
     */
    private fun drawnOneByOne(total: Int): List<Int> {
        val order = mutableListOf<Int>()
        repeat(total) {
            order += documents.nextDrawn()
            documents.release(order.last())
        }
        documents.drawnSoon()?.let(order::add)
        return order
    }

    @Test
    fun `the pages around the reader are drawn one at a time, forward first`() {
        pages.show(10)
        pages.settle(10)

        assertThat(drawnOneByOne(total = 11)).containsExactly(10, 11, 12, 9, 13, 8, 14, 7, 15, 6, 5).inOrder()
        assertThat(documents.mostAtOnce.get()).isEqualTo(1)
    }

    @Test
    fun `the window stops at both ends of the document`() {
        pages.settle(0)
        assertThat(drawnOneByOne(total = 6)).containsExactly(0, 1, 2, 3, 4, 5).inOrder()

        pages.settle(49)
        assertThat(drawnOneByOne(total = 6)).containsExactly(49, 48, 47, 46, 45, 44).inOrder()
    }

    @Test
    fun `the page shown is drawn next, ahead of the window that waits`() {
        pages.settle(10)
        assertThat(documents.nextDrawn()).isEqualTo(10)

        pages.show(30)
        // One drawing at a time: the page shown waits for the one being made, and for no other.
        assertThat(documents.drawnSoon()).isNull()
        documents.release(10)

        assertThat(documents.nextDrawn()).isEqualTo(30)
        documents.release(30)
        assertThat(documents.nextDrawn()).isEqualTo(11)
        assertThat(documents.mostAtOnce.get()).isEqualTo(1)
    }

    @Test
    fun `a jump drops the window that waited`() {
        pages.settle(10)
        assertThat(documents.nextDrawn()).isEqualTo(10)

        pages.jump(30)
        documents.release(10)

        assertThat(drawnOneByOne(total = 11)).containsExactly(30, 31, 32, 29, 33, 28, 34, 27, 35, 26, 25).inOrder()
    }

    @Test
    fun `a sharper drawing waits for the page shown and goes before the window`(): Unit = runBlocking {
        pages.show(10)
        assertThat(documents.nextDrawn()).isEqualTo(10)
        documents.release(10)
        pages.drawnAt(10, FirstLongSidePx)
        pages.settle(10)
        assertThat(documents.nextDrawn()).isEqualTo(11)

        pages.sharpen(10, 4096)
        pages.show(20)
        documents.release(11)

        assertThat(documents.nextDrawn()).isEqualTo(20)
        documents.release(20)
        assertThat(documents.nextDrawn()).isEqualTo(10)
        documents.release(10)
        assertThat(documents.nextDrawn()).isEqualTo(12)
        assertThat(documents.mostAtOnce.get()).isEqualTo(1)
    }
}
