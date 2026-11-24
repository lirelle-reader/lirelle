package app.tfdn.lirelle.core.comics.store

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Test

private const val WaitSeconds = 10L

/** Holding and giving back an album, which the main thread does, while the store works on the disk. */
class ComicStoreWaitTest {

    private val directory: File = Files.createTempDirectory("comics").toFile()

    /** The store is at work on the disk from [atWork] until [goOn]: the time is read in the middle of it. */
    private val atWork = CountDownLatch(1)
    private val goOn = CountDownLatch(1)
    private val store = ComicStore(directory, limitBytes = Long.MAX_VALUE) {
        atWork.countDown()
        goOn.await(WaitSeconds, TimeUnit.SECONDS)
        0L
    }

    @After
    fun tearDown() {
        goOn.countDown()
        directory.deleteRecursively()
    }

    @Test
    fun `an album is held and given back without waiting for the disk work on another`() {
        val first = store.hold(ComicKey(FileId("1"), "v1"))
        val working = thread { store.prepare(first) }
        assertThat(atWork.await(WaitSeconds, TimeUnit.SECONDS)).isTrue()

        val done = CountDownLatch(1)
        thread {
            store.release(store.hold(ComicKey(FileId("2"), "v1")))
            done.countDown()
        }
        val free = done.await(WaitSeconds / 2, TimeUnit.SECONDS)
        goOn.countDown()
        working.join()

        assertThat(free).isTrue()
    }

    @Test
    fun `an album held while the store was at work stays when the work ends`() {
        val pages = "x".repeat(100)
        val old = File(directory, "2-v1").apply { mkdirs() }
        File(old, "0").writeText(pages)
        val small = ComicStore(directory, limitBytes = 1) {
            atWork.countDown()
            goOn.await(WaitSeconds, TimeUnit.SECONDS)
            0L
        }
        val first = small.hold(ComicKey(FileId("1"), "v1"))
        val working = thread { small.prepare(first) }
        assertThat(atWork.await(WaitSeconds, TimeUnit.SECONDS)).isTrue()

        // Before the store looks for what must go.
        small.hold(ComicKey(FileId("2"), "v1"))
        goOn.countDown()
        working.join()

        assertThat(File(old, "0").readText()).isEqualTo(pages)
    }
}
