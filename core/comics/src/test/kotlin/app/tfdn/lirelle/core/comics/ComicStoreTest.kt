package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** What stays on the device: the bound, the versions, the closing of the profile. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicStoreTest {

    private lateinit var bench: ComicsBench

    @After
    fun tearDown() = bench.close()

    /** Reads the first page of an album at [time], and leaves it. */
    private suspend fun read(fileId: FileId, time: Long, version: String = "v1") {
        bench.time = time
        val pages = bench.open(fileId, version)
        pages.show(0)
        pages.ready(0)
        pages.close()
    }

    /** A store that has room for [pages] pages and a little more. */
    private fun benchFor(pages: Int) {
        val pageBytes = PortraitPage.size
        bench = ComicsBench(limitBytes = pages * pageBytes + pageBytes / 2L)
    }

    @Test
    fun `past the bound the album opened longest ago goes`(): Unit = runBlocking {
        benchFor(pages = 2)
        read(fileId = FileId("1"), time = 20_000)
        read(fileId = FileId("2"), time = 10_000)
        assertThat(bench.albums()).containsExactly("1-v1", "2-v1")

        read(fileId = FileId("3"), time = 30_000)

        assertThat(bench.albums()).containsExactly("1-v1", "3-v1")
    }

    @Test
    fun `an album a reader has open stays, however old and however large`(): Unit = runBlocking {
        benchFor(pages = 1)
        bench.time = 10_000
        val open = bench.open(fileId = FileId("1"))
        open.show(0)
        open.show(1)
        open.ready(0)
        open.ready(1)
        read(fileId = FileId("2"), time = 20_000)

        read(fileId = FileId("3"), time = 30_000)

        assertThat(bench.albums()).containsExactly("1-v1", "3-v1")
        assertThat(open.ready(0).file.isFile).isTrue()
    }

    @Test
    fun `an album left by its reader can go`(): Unit = runBlocking {
        benchFor(pages = 1)
        read(fileId = FileId("1"), time = 10_000)

        read(fileId = FileId("2"), time = 20_000)

        assertThat(bench.albums()).containsExactly("2-v1")
    }

    @Test
    fun `an album whose reader's scope ended can go`(): Unit = runBlocking {
        bench = ComicsBench(limitBytes = 1)
        bench.time = 10_000
        val abandoned = bench.open(fileId = FileId("1"))
        abandoned.show(0)
        abandoned.ready(0)
        // The hold is given back as the reader's work ends, with its scope.
        bench.scope.coroutineContext.job.cancelAndJoin()
        bench.store.trim()

        assertThat(bench.albums()).isEmpty()
    }

    @Test
    fun `a new version of a file drops the pages of the old one`(): Unit = runBlocking {
        bench = ComicsBench()
        read(fileId = FileId("1"), time = 10_000, version = "2026-11-17T09:12:44.501Z")
        read(fileId = FileId("12"), time = 10_000)
        assertThat(bench.albums()).containsExactly("1-2026_11_17T09_12_44_501Z", "12-v1")

        read(fileId = FileId("1"), time = 20_000, version = "v2")

        assertThat(bench.albums()).containsExactly("1-v2", "12-v1")
        assertThat(List(3) { bench.web.nextAsked() }).containsExactly(0, 0, 0)
    }

    @Test
    fun `a new version of a file leaves alone the album of a file whose id starts with its id and a dash`(): Unit =
        runBlocking {
            bench = ComicsBench()
            // The album of the file "1-a" in its version v1, as the store names it.
            File(bench.directory, "1-a-v1").mkdirs()
            read(fileId = FileId("1"), time = 10_000)

            read(fileId = FileId("1"), time = 20_000, version = "v2")

            assertThat(bench.albums()).containsExactly("1-a-v1", "1-v2")
        }

    @Test
    fun `a file without a version has a folder too`(): Unit = runBlocking {
        bench = ComicsBench()
        bench.time = 10_000
        val pages = bench.open(fileId = FileId("1"), version = null)
        pages.show(0)

        assertThat(pages.ready(0).file.parentFile?.name).isEqualTo("1-none")
    }

    /**
     * A reader outlives its profile by a moment. The profile's folder is deleted once the profile closed: a
     * page that comes then, or an album opened then, is not to make it again.
     */
    @Test
    fun `once the profile closed a page that comes is not kept, and the store's folder is not made again`(): Unit =
        runBlocking {
            bench = ComicsBench()
            val open = bench.open(fileId = FileId("1"))
            open.show(0)
            open.ready(0)

            bench.store.close()
            // A page of the window still on its way may drop its file while the folder goes: until nothing is left.
            withTimeout(10.seconds) { while (bench.directory.exists()) bench.directory.deleteRecursively() }
            open.show(1)
            val late = bench.open(fileId = FileId("2"))
            late.show(0)

            // Each fails as a page the device could not keep; the count, which the server gave, is only not noted.
            open.failed(1)
            late.failed(0)
            assertThat(late.pageCount()).isEqualTo(PageCountResult.Known(bench.web.pageCount))
            assertThat(bench.directory.exists()).isFalse()
        }
}
