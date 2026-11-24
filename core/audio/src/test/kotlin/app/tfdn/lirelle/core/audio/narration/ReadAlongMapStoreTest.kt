package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReadAlongRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadAlongMapStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val repository = FakeReadAlongRepository().apply {
        playlistResult = ApiResult.Success(ReadAlongFixtures.playlist("gap"))
        spineResult = ApiResult.Success(ReadAlongFixtures.spine("gap"))
    }

    private val book = BookId("3")

    private var time = 0L

    private val directory get() = File(folder.root, "readalong")

    // A second apart for every use: file times may be kept to the second.
    private fun store() = FileReadAlongMapStore(directory, repository, now = { time += 1_000; time })

    private fun ReadAlongMap.summary() = listOf(
        bookId, fileId, contentVersion, tracks, sections, totalMs,
        sentenceAt(24_300), fragmentsOf(6), skippedBetween(4, 6), clockSeconds(3_450_880),
    )

    @Test
    fun `the map is fetched once, playlist and spine, and kept`() = runTest {
        val store = store()

        val first = store.map(book, FileId("4"), "v1")!!
        val second = store.map(book, FileId("4"), "v1")!!

        assertThat(repository.calls).containsExactly("playlist 3 4", "spine 3 4")
        assertThat(second.summary()).isEqualTo(first.summary())
        assertThat(first.contentVersion).isEqualTo("v1")
        assertThat(first.skippedBetween(4, 6).single().sizeBytes).isEqualTo(73_306)
    }

    /** Runs what it is given at once, counting it: where the work was sent. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            block.run()
        }
    }

    @Test
    fun `a fetched map is made off the caller's thread`() = runTest {
        val compute = CountingDispatcher()
        val store = FileReadAlongMapStore(directory, repository, now = { time += 1_000; time }, compute = compute)

        store.map(book, FileId("4"), "v1")!!

        assertThat(compute.dispatched).isGreaterThan(0)
    }

    @Test
    fun `a new instance reads the map back from the device`() = runTest {
        val fetched = store().map(book, FileId("4"), "v1")!!
        repository.calls.clear()

        val read = store().map(book, FileId("4"), "v1")!!

        assertThat(repository.calls).isEmpty()
        assertThat(read.summary()).isEqualTo(fetched.summary())
    }

    @Test
    fun `another content version is fetched again and replaces the older one`() = runTest {
        store().map(book, FileId("4"), "v1")
        repository.calls.clear()

        val newer = store().map(book, FileId("4"), "v2")!!
        val unversioned = store().map(book, FileId("4"), null)!!

        assertThat(repository.calls).containsExactly("playlist 3 4", "spine 3 4", "playlist 3 4", "spine 3 4")
        assertThat(newer.contentVersion).isEqualTo("v2")
        assertThat(unversioned.contentVersion).isNull()
        assertThat(directory.listFiles()!!.toList()).hasSize(1)
    }

    @Test
    fun `a map leaves alone the map of a file whose id starts with its id and a dash`() = runTest {
        store().map(book, FileId("4-a"), "v1")

        store().map(book, FileId("4"), "v1")
        repository.calls.clear()

        assertThat(store().map(book, FileId("4-a"), "v1")).isNotNull()
        assertThat(repository.calls).isEmpty()
        assertThat(directory.listFiles()!!.toList()).hasSize(2)
    }

    @Test
    fun `a map that cannot be had is null and nothing is kept`() = runTest {
        repository.playlistResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val store = store()

        assertThat(store.map(book, FileId("4"), "v1")).isNull()

        repository.playlistResult = ApiResult.Success(ReadAlongFixtures.playlist("gap"))
        repository.spineResult = ApiResult.Failure(ApiError.ServerFailed)
        assertThat(store.map(book, FileId("4"), "v1")).isNull()
        assertThat(directory.listFiles().orEmpty().toList()).isEmpty()

        repository.spineResult = ApiResult.Success(ReadAlongFixtures.spine("gap"))
        assertThat(store.map(book, FileId("4"), "v1")).isNotNull()
    }

    @Test
    fun `a corrupt file reads as missing`() = runTest {
        store().map(book, FileId("4"), "v1")
        directory.listFiles()!!.forEach { it.writeText("not a map") }
        repository.calls.clear()

        val map = store().map(book, FileId("4"), "v1")

        assertThat(map).isNotNull()
        assertThat(repository.calls).containsExactly("playlist 3 4", "spine 3 4")
    }

    @Test
    fun `what an interrupted write left is dropped`() = runTest {
        directory.mkdirs()
        val leftover = File(directory, "9-none.map.gz.partial").apply { writeText("half a map") }

        store().map(book, FileId("4"), "v1")

        assertThat(leftover.exists()).isFalse()
        assertThat(directory.listFiles()!!.toList()).hasSize(1)
    }

    /** Its profile closed, its folder is being deleted: a late write would make it again, with a book's text. */
    @Test
    fun `once closed, a map still fetched is given but not kept, and its folder is not made again`() = runTest {
        val store = store()
        store.close()

        assertThat(store.map(book, FileId("4"), "v1")).isNotNull()

        assertThat(directory.exists()).isFalse()
    }

    @Test
    fun `at most ten maps are kept, the least recently used out`() = runTest {
        val store = store()
        for (number in 1..10) store.map(book, FileId("$number"), "v1")
        store.map(book, FileId("1"), "v1")
        store.map(book, FileId("11"), "v1")
        repository.calls.clear()

        val reopened = store()
        reopened.map(book, FileId("1"), "v1")
        reopened.map(book, FileId("11"), "v1")
        assertThat(repository.calls).isEmpty()
        reopened.map(book, FileId("2"), "v1")
        assertThat(repository.calls).containsExactly("playlist 3 2", "spine 3 2")
        assertThat(directory.listFiles()!!.toList()).hasSize(10)
    }

    @Test
    fun `the stored form is small, parallel arrays compressed`() = runTest {
        store().map(book, FileId("4"), "v1")

        val file = directory.listFiles()!!.single()
        // The recorded answer is 242 KB of JSON.
        assertThat(file.length()).isLessThan(20_000L)
    }

    // --- a copy for a download

    @Test
    fun `a kept map is copied as it is stored, and reads back the same`() = runTest {
        val store = store()
        val fetched = store.map(book, FileId("4"), "v1")!!
        repository.calls.clear()
        val copy = File(folder.root, "downloads/3/map.gz")

        assertThat(store.copyTo(book, FileId("4"), "v1", copy)).isTrue()

        assertThat(repository.calls).isEmpty()
        assertThat(readAlongMapFrom(copy, book, FileId("4"))!!.summary()).isEqualTo(fetched.summary())
        assertThat(copy.parentFile!!.list()).asList().containsExactly("map.gz")
    }

    /** The copy goes to the profile's downloads, whose folder is being deleted too. */
    @Test
    fun `once closed, a kept map is not copied, and the folder of the copy is not made`() = runTest {
        val store = store()
        store.map(book, FileId("4"), "v1")
        val copy = File(folder.root, "downloads/3/map.gz")
        store.close()

        assertThat(store.copyTo(book, FileId("4"), "v1", copy)).isFalse()

        assertThat(File(folder.root, "downloads").exists()).isFalse()
    }

    @Test
    fun `a map the store does not have is fetched for the copy`() = runTest {
        val copy = File(folder.root, "map.gz")

        assertThat(store().copyTo(book, FileId("4"), "v1", copy)).isTrue()

        assertThat(repository.calls).containsExactly("playlist 3 4", "spine 3 4")
        assertThat(readAlongMapFrom(copy, book, FileId("4"))!!.contentVersion).isEqualTo("v1")
    }

    @Test
    fun `the copy outlives the store's own file`() = runTest {
        val store = store()
        val copy = File(folder.root, "map.gz")
        store.copyTo(book, FileId("4"), "v1", copy)

        directory.listFiles().orEmpty().forEach { it.delete() }

        assertThat(readAlongMapFrom(copy, book, FileId("4"))).isNotNull()
    }

    @Test
    fun `a map that cannot be had is not copied`() = runTest {
        repository.playlistResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val copy = File(folder.root, "map.gz")

        assertThat(store().copyTo(book, FileId("4"), "v1", copy)).isFalse()

        assertThat(copy.exists()).isFalse()
    }

    @Test
    fun `a copy of another file, or one that does not read, is no map`() = runTest {
        val copy = File(folder.root, "map.gz")
        store().copyTo(book, FileId("4"), "v1", copy)
        val broken = File(folder.root, "broken.gz").apply { writeText("not a map") }

        assertThat(readAlongMapFrom(copy, book, FileId("5"))).isNull()
        assertThat(readAlongMapFrom(broken, book, FileId("4"))).isNull()
        assertThat(readAlongMapFrom(File(folder.root, "missing.gz"), book, FileId("4"))).isNull()
    }
}
