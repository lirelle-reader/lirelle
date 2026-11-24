package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.engine.FakeServedPages
import app.tfdn.lirelle.core.downloads.engine.jpeg
import app.tfdn.lirelle.core.downloads.engine.pageFiles
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderCachedPagesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val served = FakeServedPages()
    private val cached = ReaderCachedPages(served)
    private val into: File by lazy { File(folder.newFolder("pages"), "2.copy") }
    private val picture = jpeg(300)

    private fun page(index: Int = 2, version: String? = "v1", fileId: FileId = FileId("12")): PlannedFile =
        pageFiles(fileId, PagedKind.Comic, index + 1, version)[index]

    /** The page reader has seen the page 2 of the file 12, in the version v1. */
    private fun seen(bytes: ByteArray = picture): File = folder.newFile("12-v1-2").also {
        it.writeBytes(bytes)
        served.seen[Triple(FileId("12"), "v1", 2)] = it
    }

    @Test
    fun `the page seen of the same file and version is copied, and stays in the reader's cache`() {
        val source = seen()

        assertThat(cached.copyOf(page(), into)).isTrue()

        assertThat(into.readBytes()).isEqualTo(picture)
        assertThat(source.readBytes()).isEqualTo(picture)
        assertThat(into.parentFile?.list()).asList().containsExactly(into.name)
    }

    @Test
    fun `a page of a DjVu is found by its file as a comic's is`() {
        seen()
        val djvu = pageFiles(FileId("12"), PagedKind.Djvu, 3, "v1")[2]

        assertThat(cached.copyOf(djvu, into)).isTrue()
    }

    @Test
    fun `another page, another version or another file is not copied`() {
        seen()

        assertThat(cached.copyOf(page(index = 1), into)).isFalse()
        assertThat(cached.copyOf(page(version = "v2"), into)).isFalse()
        assertThat(cached.copyOf(page(version = null), into)).isFalse()
        assertThat(cached.copyOf(page(fileId = FileId("13")), into)).isFalse()
        assertThat(into.parentFile?.list()).isEmpty()
    }

    @Test
    fun `what is not a page of a served book is not looked for among the pages seen`() {
        seen()
        val named =
            PlannedFile("2", FileSource.Whole(FileId("12")), "v1", null, resumable = false, kind = FileKind.Book)

        assertThat(cached.copyOf(named, into)).isFalse()
        assertThat(cached.copyOf(page().copy(kind = FileKind.Audio), into)).isFalse()
        assertThat(cached.copyOf(page().copy(source = FileSource.Whole(FileId("12"))), into)).isFalse()
    }

    @Test
    fun `a file among the pages seen that is no picture is not copied`() {
        seen("not a picture".toByteArray())

        assertThat(cached.copyOf(page(), into)).isFalse()

        assertThat(into.parentFile?.list()).isEmpty()
    }

    @Test
    fun `a page that went from the reader's cache is not copied`() {
        seen().delete()

        assertThat(cached.copyOf(page(), into)).isFalse()
        assertThat(into.parentFile?.list()).isEmpty()
    }
}
