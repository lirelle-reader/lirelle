package app.tfdn.lirelle.core.comics

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Which pages are asked of the server, in which order and how many at a time: every answer is held. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicPagesOrderTest {

    private val bench = ComicsBench()
    private val web = bench.web
    private lateinit var pages: ComicPages

    @Before
    fun setUp() = runBlocking {
        web.pageCount = 50
        web.holding = true
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
     * Lets the answers go one at a time, in the order the pages were asked for, and gives that order: [first]
     * are being fetched, and [total] pages are expected in all. Each answer let go has the next page asked
     * for, which is waited for as long as a slow machine needs; past [total], none should come.
     */
    private fun askedOneByOne(vararg first: Int, total: Int): List<Int> {
        val order = first.toMutableList()
        var released = 0
        while (released < order.size) {
            web.release(order[released++])
            if (order.size < total) order += web.nextAsked() else web.askedSoon()?.let(order::add)
        }
        return order
    }

    @Test
    fun `opening at the first page fetches what follows, two at a time`() {
        pages.show(0)
        assertThat(web.nextAsked()).isEqualTo(0)
        pages.settle(0)
        assertThat(web.nextAsked()).isEqualTo(1)
        assertThat(web.askedSoon()).isNull()

        assertThat(askedOneByOne(0, 1, total = 6)).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun `opening in the middle fetches both sides, forward first`() {
        pages.show(10)
        assertThat(web.nextAsked()).isEqualTo(10)
        pages.settle(10)
        assertThat(web.nextAsked()).isEqualTo(11)

        assertThat(askedOneByOne(10, 11, total = 11)).containsExactly(10, 11, 12, 9, 13, 8, 14, 7, 15, 6, 5).inOrder()
    }

    @Test
    fun `the page shown is asked for at once when both slots are busy, and no third of the window`() {
        pages.settle(10)
        assertThat(web.nextAsked(2)).containsExactly(10, 11)
        assertThat(web.askedSoon()).isNull()

        pages.show(30)
        assertThat(web.nextAsked()).isEqualTo(30)

        // The page shown had a slot of its own: giving it back starts nothing.
        web.release(30)
        assertThat(web.askedSoon()).isNull()
        web.release(10)
        assertThat(web.nextAsked()).isEqualTo(12)
        assertThat(web.askedSoon()).isNull()
    }

    @Test
    fun `a page shown while it is being fetched is not asked for again`() {
        pages.settle(10)
        assertThat(web.nextAsked(2)).containsExactly(10, 11)

        pages.show(11)

        assertThat(web.askedSoon()).isNull()
    }

    @Test
    fun `leafing asks for the pages passed and none of their neighbours`() {
        (0..3).forEach(pages::show)

        assertThat(web.nextAsked(4)).containsExactly(0, 1, 2, 3)
        assertThat(web.askedSoon()).isNull()
    }

    @Test
    fun `a jump drops what waited and the fetches left behind`(): Unit = runBlocking {
        pages.show(0)
        assertThat(web.nextAsked()).isEqualTo(0)
        pages.settle(0)
        assertThat(web.nextAsked()).isEqualTo(1)

        pages.jump(30)

        assertThat(web.nextAsked(2)).containsExactly(30, 31)
        assertThat(web.askedSoon()).isNull()
        web.release(30)
        assertThat(web.nextAsked()).isEqualTo(32)
        // What was being fetched far behind is not kept.
        web.release(0)
        web.release(1)
        assertThat(pages.ready(30).file.name).isEqualTo("30")
        assertThat(web.askedSoon()).isNull()
        assertThat(bench.kept("$AlbumFileId-v1")).containsNoneOf("0", "1")
    }

    @Test
    fun `a jump nearby lets the fetches of its window go on`() {
        pages.show(0)
        assertThat(web.nextAsked()).isEqualTo(0)
        pages.settle(0)
        assertThat(web.nextAsked()).isEqualTo(1)

        pages.jump(3)

        assertThat(web.nextAsked()).isEqualTo(3)
        assertThat(web.askedSoon()).isNull()
        web.release(0)
        assertThat(web.askedSoon()).isNull()
        web.release(1)
        assertThat(web.nextAsked()).isEqualTo(4)
        assertThat(runBlocking { pages.ready(0) }.file).isEqualTo(File(bench.directory, "$AlbumFileId-v1/0"))
    }

    @Test
    fun `the window stops at the end of the album`() {
        pages.show(49)
        assertThat(web.nextAsked()).isEqualTo(49)
        pages.settle(49)
        assertThat(web.nextAsked()).isEqualTo(48)

        assertThat(askedOneByOne(49, 48, total = 6)).containsExactly(49, 48, 47, 46, 45, 44).inOrder()
    }
}
