package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderCachedEpubsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cache = OneBookCache()
    private val cached = ReaderCachedEpubs(cache)
    private val into: File by lazy { File(folder.newFolder("downloads"), "12.epub.copy") }

    /** A kept file drawn from the book's file [fileId], whatever its [name]. */
    private fun planned(
        name: String = "12.epub",
        version: String? = "v1",
        kind: FileKind = FileKind.Epub,
        fileId: FileId = FileId("12"),
    ) = PlannedFile(name, FileSource.Whole(fileId), version, expectedBytes = null, resumable = false, kind)

    /** The reader's cache has the file 12 in the version v1. */
    private fun shelve(): File = folder.newFile("12-v1.epub").also {
        it.writeText("the book")
        cache.files[CachedBookKey(FileId("12"), "v1")] = it
    }

    @Test
    fun `the cache's file of the same version is copied, and stays in the cache`() {
        val source = shelve()

        assertThat(cached.copyOf(planned(), into)).isTrue()

        assertThat(into.readText()).isEqualTo("the book")
        assertThat(source.readText()).isEqualTo("the book")
        assertThat(into.parentFile?.list()).asList().containsExactly(into.name)
    }

    @Test
    fun `a PDF and a Kindle book the readers fetched are copied as an EPUB is`() {
        shelve()

        assertThat(cached.copyOf(planned(name = "12.pdf", kind = FileKind.Pdf), into)).isTrue()
        assertThat(into.readText()).isEqualTo("the book")
        into.delete()
        assertThat(cached.copyOf(planned(name = "12.azw3", kind = FileKind.Book), into)).isTrue()
        val other = planned(name = "13.pdf", kind = FileKind.Pdf, fileId = FileId("13"))
        assertThat(cached.copyOf(other, File(into.path + "2"))).isFalse()
    }

    @Test
    fun `another version, another file or a file that is not an EPUB is not copied`() {
        shelve()

        assertThat(cached.copyOf(planned(version = "v2"), into)).isFalse()
        assertThat(cached.copyOf(planned(version = null), into)).isFalse()
        assertThat(cached.copyOf(planned(name = "13.epub", fileId = FileId("13")), into)).isFalse()
        assertThat(cached.copyOf(planned(name = "000-12.epub.audio", kind = FileKind.Audio), into)).isFalse()
        assertThat(cached.copyOf(planned(name = "12.epub", kind = FileKind.Audio), into)).isFalse()

        assertThat(into.exists()).isFalse()
        assertThat(cache.asked).doesNotContain(CachedBookKey(FileId("12"), "v1"))
    }

    @Test
    fun `a copy that cannot be written is not one, and leaves nothing`() {
        shelve()
        val nowhere = File(folder.root, "missing/12.epub.copy")

        assertThat(cached.copyOf(planned(), nowhere)).isFalse()

        assertThat(nowhere.parentFile?.exists()).isFalse()
    }

    private class OneBookCache : ReaderCache {
        val files = mutableMapOf<CachedBookKey, File>()
        val asked = mutableListOf<CachedBookKey>()

        override fun cached(key: CachedBookKey): File? {
            asked += key
            return files[key]
        }

        override fun open(bookId: BookId, key: CachedBookKey, audioless: Boolean): Flow<CacheState> = emptyFlow()

        override fun hold(key: CachedBookKey) = Unit

        override fun release(key: CachedBookKey) = Unit

        override suspend fun discard(key: CachedBookKey) = Unit
    }
}
