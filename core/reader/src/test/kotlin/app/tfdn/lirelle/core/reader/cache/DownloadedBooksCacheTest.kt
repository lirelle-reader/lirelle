package app.tfdn.lirelle.core.reader.cache

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.fileName
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The copy the device keeps of a downloaded book comes before the cache and the server. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedBooksCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val repository = FakeReaderRepository().apply { fileContent = ByteArray(BookSize) { it.toByte() } }
    private val directory: File by lazy { File(folder.root, "reader") }
    private val downloaded = KeptEpubs()
    private val holds = RecordingHolds()

    private fun TestScope.cache() = DefaultReaderCache(
        repository = repository,
        directory = directory,
        limitBytes = Long.MAX_VALUE,
        io = UnconfinedTestDispatcher(testScheduler),
        downloaded = downloaded,
        books = holds,
    )

    private fun key(fileId: String, version: String? = "v1") = CachedBookKey(FileId(fileId), version)

    /** A book of the downloads, outside the cache's directory. */
    private fun keep(bookId: BookId, fileId: String): File {
        val file = File(folder.root, "downloads/${bookId.fileName}/epub/$fileId.epub")
        file.parentFile?.mkdirs()
        file.writeText("kept $fileId")
        downloaded.files[bookId to FileId(fileId)] = file
        return file
    }

    @Test
    fun `a downloaded book is ready at once, as the kept file itself, with no request`() = runTest {
        val kept = keep(bookId = Kept, fileId = "12")

        val states = cache().open(Kept, key("12"), audioless = false).toList()

        assertThat(states).containsExactly(CacheState.Ready(kept, kept = true))
        assertThat(repository.downloads).isEmpty()
        assertThat(directory.list().orEmpty()).isEmpty()
    }

    @Test
    fun `the kept copy is used whatever the version asked`() = runTest {
        val kept = keep(bookId = Kept, fileId = "12")

        val states = cache().open(Kept, key("12", version = "what the server has now"), audioless = true).toList()

        assertThat(states).containsExactly(CacheState.Ready(kept, kept = true))
        assertThat(repository.downloads).isEmpty()
    }

    @Test
    fun `a book that is not downloaded is fetched as before`() = runTest {
        keep(bookId = Kept, fileId = "12")
        val cache = cache()

        val other = cache.open(Other, key("12"), audioless = false).toList().last() as CacheState.Ready
        cache.hold(key("12"))
        cache.release(key("12"))

        assertThat(other.file.parentFile).isEqualTo(directory)
        assertThat(repository.downloads).containsExactly(FileId("12") to false)
        assertThat(holds.stillHeld).isEmpty()
        // Only for the time of the look: the reader's own hold is the cache's.
        assertThat(holds.held).containsExactly(Other)
    }

    @Test
    fun `holding and releasing a downloaded book say that the book is in use`() = runTest {
        keep(bookId = Kept, fileId = "12")
        val cache = cache()

        cache.open(Kept, key("12"), audioless = false).collect { cache.hold(key("12")) }

        assertThat(holds.stillHeld).containsExactly(Kept)

        cache.release(key("12"))

        assertThat(holds.stillHeld).isEmpty()
    }

    @Test
    fun `an opening that nobody holds leaves the downloaded book free`() = runTest {
        keep(bookId = Kept, fileId = "12")

        cache().open(Kept, key("12"), audioless = false).toList()

        assertThat(holds.held).contains(Kept)
        assertThat(holds.stillHeld).isEmpty()
    }

    @Test
    fun `a book held from the cache is released there, even once it is downloaded`() = runTest {
        val cache = cache()
        cache.open(Kept, key("12"), audioless = false).collect { if (it is CacheState.Ready) cache.hold(key("12")) }
        keep(bookId = Kept, fileId = "12")
        cache.open(Kept, key("12"), audioless = false).collect { cache.hold(key("12")) }

        cache.release(key("12"))
        assertThat(holds.stillHeld).isEmpty()

        // The cache's own copy is still held by the first reader: nothing may evict it.
        cache.release(key("12"))
        assertThat(holds.stillHeld).isEmpty()
    }

    @Test
    fun `discarding leaves the downloaded file alone`() = runTest {
        val kept = keep(bookId = Kept, fileId = "12")
        val cache = cache()
        cache.open(Kept, key("12"), audioless = false).toList()

        cache.discard(key("12"))

        assertThat(kept.readText()).isEqualTo("kept 12")
    }

    @Test
    fun `the cache gives its whole file of a version, and nothing of another`() = runTest {
        val cache = cache()
        val ready = cache.open(Kept, key("12"), audioless = false).toList().last() as CacheState.Ready

        assertThat(cache.cached(key("12"))).isEqualTo(ready.file)
        assertThat(cache.cached(key("12", version = "v2"))).isNull()
        assertThat(cache.cached(key("13"))).isNull()
    }

    private class KeptEpubs : DownloadedFiles {
        val files = mutableMapOf<Pair<BookId, FileId>, File>()

        override suspend fun text(bookId: BookId, fileId: FileId): File? = files[bookId to fileId]

        override fun audio(url: String): File? = null
    }

    private class RecordingHolds : BookHolds {
        val held = mutableListOf<BookId>()
        val released = mutableListOf<BookId>()
        val stillHeld: List<BookId> get() = held.toMutableList().also { left -> released.forEach { left.remove(it) } }

        override fun hold(bookId: BookId) {
            held += bookId
        }

        override fun release(bookId: BookId) {
            released += bookId
        }
    }

    private companion object {
        const val BookSize = 100

        /** The downloaded book. */
        val Kept = BookId("7")

        /** A book that is not downloaded. */
        val Other = BookId("8")
    }
}
