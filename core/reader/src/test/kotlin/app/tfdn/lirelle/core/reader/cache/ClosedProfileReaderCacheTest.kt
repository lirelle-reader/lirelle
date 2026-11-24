package app.tfdn.lirelle.core.reader.cache

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A reader that is leaving may still be fetching its book when its profile closes; at a sign-out the profile's
 * cache folder is deleted next. What the cache does after that must not bring the folder back, with a book of
 * the account that left in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClosedProfileReaderCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val repository = FakeReaderRepository().apply { fileContent = ByteArray(64) { it.toByte() } }
    private val directory: File by lazy { File(folder.root, "cache/profiles/p1/reader") }

    private fun TestScope.cache() = DefaultReaderCache(
        repository = repository,
        directory = directory,
        limitBytes = Long.MAX_VALUE,
        io = UnconfinedTestDispatcher(testScheduler),
    )

    private val book = BookId("1")

    /** The key of the file whose id is the text of [fileId]. */
    private fun key(fileId: Int) = CachedBookKey(FileId(fileId.toString()), "v1")

    /** The profile closed and was removed: its cache folder is deleted. */
    private suspend fun DefaultReaderCache.closeAndRemove() {
        close()
        assertThat(directory.deleteRecursively()).isTrue()
    }

    @Test
    fun `a book fetched once the profile closed is not asked for, and the folder is not made again`() = runTest {
        val cache = cache()
        cache.open(book, key(12), audioless = false).toList()
        assertThat(directory.list()).asList().hasSize(1)
        cache.closeAndRemove()

        val state = cache.open(book, key(13), audioless = false).last()

        assertThat(state).isEqualTo(CacheState.Failed(ApiError.Unexpected))
        assertThat(repository.downloads).containsExactly(FileId("12") to false)
        assertThat(directory.exists()).isFalse()
    }

    @Test
    fun `a cache that never made its folder does not make it once closed`() = runTest {
        val cache = cache()
        cache.close()

        val state = cache.open(book, key(12), audioless = false).last()

        assertThat(state).isEqualTo(CacheState.Failed(ApiError.Unexpected))
        assertThat(directory.exists()).isFalse()
    }

    @Test
    fun `a book already there is still given once the profile closed`() = runTest {
        val cache = cache()
        cache.open(book, key(12), audioless = false).toList()
        cache.close()

        val state = cache.open(book, key(12), audioless = false).last()

        assertThat((state as CacheState.Ready).file.readBytes()).isEqualTo(repository.fileContent)
        assertThat(repository.downloads).hasSize(1)
    }

    @Test
    fun `a book being fetched as the profile closes is not kept, and the folder is not made again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.downloadGate = gate
        val cache = cache()
        val fetching = async { cache.open(book, key(12), audioless = false).last() }
        runCurrent()
        assertThat(directory.list()).asList().hasSize(1)
        cache.closeAndRemove()

        gate.complete(Unit)

        assertThat(fetching.await()).isEqualTo(CacheState.Failed(ApiError.Unexpected))
        assertThat(directory.exists()).isFalse()
    }
}
