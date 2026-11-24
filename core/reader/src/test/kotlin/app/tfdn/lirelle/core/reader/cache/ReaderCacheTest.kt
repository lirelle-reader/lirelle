package app.tfdn.lirelle.core.reader.cache

import app.cash.turbine.test
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val repository = FakeReaderRepository().apply { fileContent = ByteArray(BookSize) { it.toByte() } }
    private val directory: File by lazy { File(folder.root, "reader") }
    private var time = 1_000_000L

    private fun TestScope.cache(limitBytes: Long = Long.MAX_VALUE, into: File = directory) = DefaultReaderCache(
        repository = repository,
        directory = into,
        limitBytes = limitBytes,
        io = UnconfinedTestDispatcher(testScheduler),
        now = {
            time += OneSecond
            time
        },
    )

    /** The key of the file whose id is the text of [fileId]. */
    private fun key(fileId: Int, version: String? = "2026-11-11T13:01:19.621Z") =
        CachedBookKey(FileId(fileId.toString()), version)

    private fun downloading(bytes: Long) = CacheState.Downloading(DownloadProgress(bytes, BookSize.toLong()))

    private fun names(): List<String> = directory.list().orEmpty().sorted()

    /** Opens a book to the end, as a reader that keeps it does, and returns its file. */
    private suspend fun ReaderCache.fetch(key: CachedBookKey): File {
        var file: File? = null
        open(Book, key, audioless = false).collect { state ->
            if (state is CacheState.Ready) {
                hold(key)
                file = state.file
            }
        }
        return checkNotNull(file)
    }

    @Test
    fun `a download is written under a temporary name and moved into place when complete`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.downloadGate = gate

        cache().open(Book, key(12), audioless = false).test {
            assertThat(awaitItem()).isEqualTo(downloading(0))
            assertThat(awaitItem()).isEqualTo(downloading(BookSize / 2L))
            assertThat(names()).hasSize(1)
            assertThat(names().single()).endsWith(".part")

            gate.complete(Unit)

            assertThat(awaitItem()).isEqualTo(downloading(BookSize.toLong()))
            val ready = awaitItem() as CacheState.Ready
            assertThat(ready.file.readBytes()).isEqualTo(repository.fileContent)
            assertThat(ready.file.parentFile).isEqualTo(directory)
            assertThat(names()).containsExactly(ready.file.name)
            awaitComplete()
        }
    }

    @Test
    fun `the variant asked for is the one downloaded`() = runTest {
        val cache = cache()

        cache.open(Book, key(12), audioless = true).toList()
        cache.open(Book, key(13), audioless = false).toList()

        assertThat(repository.downloads).containsExactly(FileId("12") to true, FileId("13") to false).inOrder()
    }

    @Test
    fun `a failed download leaves no file`() = runTest {
        repository.downloadResult = ApiResult.Failure(ApiError.ServerUnreachable)

        val states = cache().open(Book, key(12), audioless = false).toList()

        assertThat(states.last()).isEqualTo(CacheState.Failed(ApiError.ServerUnreachable))
        assertThat(names()).isEmpty()
    }

    @Test
    fun `a failed download can be tried again`() = runTest {
        val cache = cache()
        repository.downloadResult = ApiResult.Failure(ApiError.ServerUnreachable)
        cache.open(Book, key(12), audioless = false).toList()
        repository.downloadResult = ApiResult.Success(Unit)

        val file = cache.fetch(key(12))

        assertThat(file.readBytes()).isEqualTo(repository.fileContent)
        assertThat(repository.downloads).hasSize(2)
    }

    @Test
    fun `a cancelled download leaves no file`() = runTest {
        repository.downloadGate = CompletableDeferred()

        cache().open(Book, key(12), audioless = false).test {
            awaitItem()
            awaitItem()
            assertThat(names()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        runCurrent()

        assertThat(names()).isEmpty()
    }

    @Test
    fun `a book already there is ready at once, without a download`() = runTest {
        val cache = cache()
        val first = cache.fetch(key(12))

        val states = cache.open(Book, key(12), audioless = false).toList()

        assertThat(states).containsExactly(CacheState.Ready(first))
        assertThat(repository.downloads).hasSize(1)
    }

    @Test
    fun `a book already there opens while another one is still downloading`() = runTest {
        val cache = cache()
        val there = cache.fetch(key(1))
        repository.downloadGate = CompletableDeferred()
        val stalled = launch { cache.open(Book, key(2), audioless = false).collect { } }
        runCurrent()
        val states = mutableListOf<CacheState>()

        val opening = launch { cache.open(Book, key(1), audioless = false).collect { states += it } }
        runCurrent()

        assertThat(states).containsExactly(CacheState.Ready(there))
        opening.cancel()
        stalled.cancel()
    }

    @Test
    fun `two books are downloaded at the same time, and one book only once`() = runTest {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        repository.downloadGate = gate

        val readers = listOf(key(1), key(2), key(1)).map { key -> async { cache.fetch(key) } }
        runCurrent()

        assertThat(repository.downloads.map { it.first }).containsExactly(FileId("1"), FileId("2")).inOrder()
        assertThat(names()).hasSize(2)

        gate.complete(Unit)
        val files = readers.awaitAll()

        assertThat(repository.downloads).hasSize(2)
        assertThat(files[2]).isEqualTo(files[0])
        assertThat(names()).containsExactly(files[0].name, files[1].name)
        files.forEach { assertThat(it.readBytes()).isEqualTo(repository.fileContent) }
    }

    @Test
    fun `a book kept by an earlier run of the app is still there`() = runTest {
        val first = cache().fetch(key(12))

        assertThat(cache().open(Book, key(12), audioless = false).toList()).containsExactly(CacheState.Ready(first))
        assertThat(repository.downloads).hasSize(1)
    }

    @Test
    fun `another version of a file is downloaded again and replaces the old one`() = runTest {
        val cache = cache()
        val old = cache.fetch(key(12, "2026-11-11T13:01:19.621Z"))
        cache.release(key(12, "2026-11-11T13:01:19.621Z"))

        val new = cache.fetch(key(12, "2026-11-12T08:00:00.000Z"))

        assertThat(repository.downloads).hasSize(2)
        assertThat(new).isNotEqualTo(old)
        assertThat(names()).containsExactly(new.name)
    }

    @Test
    fun `files with the same version or none are told apart by their id`() = runTest {
        val cache = cache()

        val files = listOf(key(1), key(11), key(1, null), key(11, null)).map { cache.fetch(it) }

        assertThat(files.map { it.name }.toSet()).hasSize(4)
        assertThat(names()).hasSize(4)
    }

    @Test
    fun `a new version of a file leaves alone a file whose id starts with its id and a dash`() = runTest {
        val cache = cache()
        val other = CachedBookKey(FileId("12-a"), "2026-11-11T13:01:19.621Z")
        val kept = cache.fetch(other)
        cache.release(other)
        cache.fetch(key(12, "2026-11-11T13:01:19.621Z"))
        cache.release(key(12, "2026-11-11T13:01:19.621Z"))

        val new = cache.fetch(key(12, "2026-11-12T08:00:00.000Z"))

        assertThat(names()).containsExactly(kept.name, new.name)
    }

    @Test
    fun `over the limit, the least recently opened books go first`() = runTest {
        val cache = cache(limitBytes = 2L * BookSize + 1)
        val first = cache.fetch(key(1)).also { cache.release(key(1)) }
        val second = cache.fetch(key(2)).also { cache.release(key(2)) }
        cache.fetch(key(1)).also { cache.release(key(1)) }

        val third = cache.fetch(key(3))

        assertThat(names()).containsExactly(first.name, third.name)
        assertThat(second.exists()).isFalse()
    }

    @Test
    fun `the book being read is never removed, until it is released`() = runTest {
        val cache = cache(limitBytes = BookSize + 1L)
        val reading = cache.fetch(key(1))

        val second = cache.fetch(key(2)).also { cache.release(key(2)) }
        assertThat(names()).containsExactly(reading.name, second.name)

        cache.release(key(1))
        val third = cache.fetch(key(3))

        assertThat(names()).containsExactly(third.name)
    }

    @Test
    fun `a book whose opening is cancelled as it comes out ready is not held`() = runTest {
        val cache = cache(limitBytes = BookSize + 1L)
        val gate = CompletableDeferred<Unit>()
        repository.downloadGate = gate
        val seen = mutableListOf<CacheState>()
        val opening = launch { cache.open(Book, key(1), audioless = false).collect { seen += it } }
        runCurrent()

        // The book is in place and about to be handed over; the reader is left before it learns of it.
        gate.complete(Unit)
        launch { opening.cancel() }
        runCurrent()
        repository.downloadGate = null

        assertThat(seen.filterIsInstance<CacheState.Ready>()).isEmpty()
        assertThat(names()).hasSize(1)

        val second = cache.fetch(key(2))

        assertThat(names()).containsExactly(second.name)
    }

    @Test
    fun `a book that came out ready is kept only by the reader that holds it`() = runTest {
        val cache = cache(limitBytes = BookSize + 1L)
        val first = (cache.open(Book, key(1), audioless = false).toList().last() as CacheState.Ready).file

        val second = cache.fetch(key(2))
        assertThat(first.exists()).isFalse()

        // Held twice, it takes two releases.
        cache.hold(key(2))
        cache.release(key(2))
        val third = cache.fetch(key(3)).also { cache.release(key(3)) }
        assertThat(names()).containsExactly(second.name, third.name)

        cache.release(key(2))
        val fourth = cache.fetch(key(4))
        assertThat(names()).containsExactly(fourth.name)
    }

    @Test
    fun `a book is not removed while it is being handed over`() = runTest {
        val cache = cache(limitBytes = BookSize + 1L)
        cache.fetch(key(1)).also { cache.release(key(1)) }
        var there = false

        cache.open(Book, key(1), audioless = false).collect {
            // Another reader opens another book before this one has said it keeps the first.
            cache.fetch(key(2))
            there = (it as CacheState.Ready).file.exists()
        }

        assertThat(there).isTrue()
    }

    @Test
    fun `a book larger than the limit can still be read`() = runTest {
        val file = cache(limitBytes = 1).fetch(key(1))

        assertThat(file.readBytes()).isEqualTo(repository.fileContent)
    }

    @Test
    fun `a discarded book is removed, held or not, and downloaded again when asked`() = runTest {
        val cache = cache()
        val other = cache.fetch(key(2))
        val refused = cache.fetch(key(1))

        cache.discard(key(1))

        assertThat(refused.exists()).isFalse()
        assertThat(names()).containsExactly(other.name)

        cache.release(key(1))
        assertThat(cache.fetch(key(1)).readBytes()).isEqualTo(repository.fileContent)
        assertThat(repository.downloads.map { it.first })
            .containsExactly(FileId("2"), FileId("1"), FileId("1")).inOrder()
    }

    @Test
    fun `discarding a book that is not there does nothing`() = runTest {
        val cache = cache()
        val other = cache.fetch(key(2))

        cache.discard(key(1))
        cache.discard(key(2, version = "another"))

        assertThat(names()).containsExactly(other.name)
    }

    @Test
    fun `what an interrupted download left is removed at the next start`() = runTest {
        directory.mkdirs()
        File(directory, "12-interrupted.epub.part").writeText("half a book")

        val file = cache().fetch(key(1))

        assertThat(names()).containsExactly(file.name)
    }

    @Test
    fun `a directory removed while the app runs is made again for the next download`() = runTest {
        val cache = cache()
        cache.fetch(key(1)).also { cache.release(key(1)) }

        // What the system does when it clears the app's cache under a living process.
        directory.deleteRecursively()
        val file = cache.fetch(key(2))

        assertThat(file.readBytes()).isEqualTo(repository.fileContent)
        assertThat(names()).containsExactly(file.name)
    }

    @Test
    fun `a directory that cannot be written is a failure, not a crash`() = runTest {
        val blocked = File(folder.newFile("not-a-directory"), "reader")

        val states = cache(into = blocked).open(Book, key(1), audioless = false).toList()

        assertThat(states.last()).isEqualTo(CacheState.Failed(ApiError.Unexpected))
    }

    private companion object {
        val Book = BookId("7")
        const val BookSize = 100
        const val OneSecond = 1_000L
    }
}
