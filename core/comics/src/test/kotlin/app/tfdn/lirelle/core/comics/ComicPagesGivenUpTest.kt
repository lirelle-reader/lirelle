package app.tfdn.lirelle.core.comics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val WaitMillis = 5_000L
private const val LookMillis = 20L

/** A fetch given up while the picture was on its way: the answer had begun, its bytes had not all come. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicPagesGivenUpTest {

    private val bench = ComicsBench()
    private val web = bench.web
    private val album = "$AlbumFileId-v1"

    @After
    fun tearDown() = bench.close()

    private fun parts(): List<String> = bench.kept(album).filter { it.endsWith(".part") }

    /** Waits until the album holds a page being written, or none any more. */
    private suspend fun awaitParts(some: Boolean) = withTimeout(WaitMillis) {
        while (parts().isNotEmpty() != some) delay(LookMillis)
    }

    @Test
    fun `a fetch given up leaves a later fetch of the same page alone`(): Unit = runBlocking {
        // The first answer is still on its way when the page is asked for again, and ends before the second.
        web.stall(0, 700, 1_500)
        val pages = bench.open()
        pages.show(0)
        awaitParts(some = true)

        pages.jump(15)
        pages.ready(15)
        pages.jump(0)

        assertThat(pages.ready(0).file.readBytes()).isEqualTo(PortraitPage)
        awaitParts(some = false)
    }

    @Test
    fun `a fetch given up stops waiting for the bytes of its picture`(): Unit = runBlocking {
        web.stall(0, 20_000)
        val pages = bench.open()
        pages.show(0)
        awaitParts(some = true)

        pages.close()

        // Long before the server sends the picture.
        awaitParts(some = false)
        assertThat(bench.kept(album)).doesNotContain("0")
    }
}
