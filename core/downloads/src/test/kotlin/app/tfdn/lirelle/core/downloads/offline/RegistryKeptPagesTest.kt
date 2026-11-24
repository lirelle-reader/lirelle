package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.engine.MemoryRegistry
import app.tfdn.lirelle.core.downloads.engine.pageFiles
import app.tfdn.lirelle.core.downloads.engine.testLocations
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val Book = BookId("7")
private val OtherBook = BookId("8")
private val AlbumFile = FileId("12")

/** The pages kept of a comic or a DjVu, for the page reader: those of a whole album, and of no other. */
class RegistryKeptPagesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val registry = MemoryRegistry()
    private val files by lazy { DownloadFiles(File(folder.root, "downloads")) }
    private val downloaded by lazy {
        RegistryDownloadedFiles(registry, files, testLocations("https://books.example"))
    }
    private val complete = FormatState.Complete(30)

    private fun album(state: FormatState, pages: Int = 3, version: String? = "v1", removing: Boolean = false) =
        FormatRecord(
            format = DownloadFormat.Text,
            state = state,
            files = pageFiles(AlbumFile, PagedKind.Comic, pages, version),
            fileId = AlbumFile,
            version = version,
            askedAt = Instant.EPOCH,
            removing = removing,
            keptPages = pages,
        )

    /** Records [record] for the book, its files in their places but those of [missing]. */
    private suspend fun keep(record: FormatRecord, missing: Set<String> = emptySet()) {
        registry.update(Book) { DownloadedBook(Book, listOf(record), Instant.EPOCH, lastOpenedAt = null) }
        files.bookDir(Book).deleteRecursively()
        record.files.filterNot { it.name in missing }.forEach { planned ->
            val file = files.file(Book, record.format, planned.name)
            checkNotNull(file.parentFile).mkdirs()
            file.writeText("page ${planned.name}")
        }
    }

    @Test
    fun `the pages of a whole album are given, each as its file of the downloads`() = runTest {
        keep(album(complete))

        val pages = checkNotNull(downloaded.pages(Book, AlbumFile))

        assertThat(pages.count).isEqualTo(3)
        assertThat(pages.file(0)).isEqualTo(File(files.bookDir(Book), "pages/0"))
        assertThat(pages.file(2)).isEqualTo(File(files.bookDir(Book), "pages/2"))
        assertThat(pages.file(1).readText()).isEqualTo("page 1")
    }

    @Test
    fun `they are given whatever the version kept`() = runTest {
        keep(album(complete, version = "an older one"))

        assertThat(downloaded.pages(Book, AlbumFile)?.count).isEqualTo(3)
    }

    @Test
    fun `another file, or another book, has no pages`() = runTest {
        keep(album(complete))

        assertThat(downloaded.pages(Book, AlbumFile)).isNotNull()
        assertThat(downloaded.pages(Book, FileId("13"))).isNull()
        assertThat(downloaded.pages(OtherBook, AlbumFile)).isNull()
    }

    @Test
    fun `an album half fetched has none`() = runTest {
        keep(album(complete))
        assertThat(downloaded.pages(Book, AlbumFile)).isNotNull()

        keep(album(FormatState.Downloading(10, 30)).copy(keptPages = 1), missing = setOf("1", "2"))
        assertThat(downloaded.pages(Book, AlbumFile)).isNull()

        keep(album(FormatState.Queued))
        assertThat(downloaded.pages(Book, AlbumFile)).isNull()
    }

    @Test
    fun `an album being deleted has none`() = runTest {
        keep(album(complete))
        assertThat(downloaded.pages(Book, AlbumFile)).isNotNull()

        keep(album(complete, removing = true))
        assertThat(downloaded.pages(Book, AlbumFile)).isNull()
    }

    @Test
    fun `an album a page of which is no longer on the device has none`() = runTest {
        keep(album(complete))
        assertThat(downloaded.pages(Book, AlbumFile)).isNotNull()

        keep(album(complete), missing = setOf("1"))
        assertThat(downloaded.pages(Book, AlbumFile)).isNull()
    }

    @Test
    fun `a text kept as one file has no pages, and pages are not a text's file`() = runTest {
        keep(album(complete))
        assertThat(downloaded.pages(Book, AlbumFile)).isNotNull()
        assertThat(downloaded.text(Book, AlbumFile)).isNull()

        val pdf = PlannedFile("$AlbumFile.pdf", FileSource.Whole(AlbumFile), "v1", 10, true, FileKind.Pdf)
        keep(album(complete).copy(files = listOf(pdf), keptPages = 0))
        assertThat(downloaded.pages(Book, AlbumFile)).isNull()
    }

    @Test
    fun `who keeps nothing has no pages`() = runTest {
        assertThat(DownloadedFiles.None.pages(Book, AlbumFile)).isNull()
    }
}
