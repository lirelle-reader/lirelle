package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.DownloadLocations
import app.tfdn.lirelle.core.downloads.engine.MemoryRegistry
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
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TrackUrl = "https://books.example/api/v1/epub/7/media-overlay/file/audio/one.mp3?fileId=12"

private val Book7 = BookId("7")
private val Book8 = BookId("8")
private val Book9 = BookId("9")
private val Book10 = BookId("10")

@OptIn(ExperimentalCoroutinesApi::class)
class RegistryDownloadedFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val registry = MemoryRegistry()
    private val files by lazy { DownloadFiles(File(folder.root, "downloads")) }

    /**
     * The addresses the server gives: an asset's is the text it is named by, so that a test says the address
     * the player will ask; a whole file's is under the files' route.
     */
    private val locations = DownloadLocations { _, source ->
        when (source) {
            is FileSource.AudioAsset -> source.assetId
            is FileSource.Whole -> "https://books.example/files/${source.fileId}"
            else -> null
        }
    }
    private val downloaded by lazy { RegistryDownloadedFiles(registry, files, locations) }
    private val complete = FormatState.Complete(10)

    private fun epub(fileId: FileId) =
        PlannedFile("$fileId.epub", FileSource.Whole(fileId), "v1", 10, true, FileKind.Epub)

    /** An audio file the server serves at [url]. */
    private fun audio(name: String, url: String) =
        PlannedFile(name, FileSource.AudioAsset(url), "v1", 10, true, FileKind.Audio)

    private fun record(
        format: DownloadFormat,
        state: FormatState,
        vararg planned: PlannedFile,
        removing: Boolean = false,
    ) = FormatRecord(
        format, state, planned.toList(), fileId = FileId("12"), version = "v1", Instant.EPOCH, removing = removing,
    )

    /** Records [formats] for the book, each of their files in its place unless [onDisk] is false. */
    private suspend fun keep(bookId: BookId, vararg formats: FormatRecord, onDisk: Boolean = true) {
        registry.update(bookId) { DownloadedBook(bookId, formats.toList(), Instant.EPOCH, lastOpenedAt = null) }
        if (!onDisk) return
        formats.forEach { format ->
            format.files.forEach { planned ->
                val file = files.file(bookId, format.format, planned.name)
                checkNotNull(file.parentFile).mkdirs()
                file.writeText("x")
            }
        }
    }

    private fun TestScope.started(): RegistryDownloadedFiles {
        downloaded.start(backgroundScope)
        runCurrent()
        return downloaded
    }

    @Test
    fun `the EPUB of a complete text is given, as the file of the downloads`() = runTest {
        keep(Book7, record(DownloadFormat.Text, complete, epub(FileId("12"))))

        assertThat(downloaded.text(Book7, FileId("12"))).isEqualTo(files.file(Book7, DownloadFormat.Text, "12.epub"))
        assertThat(downloaded.text(Book7, FileId("13"))).isNull()
        assertThat(downloaded.text(Book8, FileId("12"))).isNull()
    }

    @Test
    fun `the text of another format is given by its file, whatever its extension`() = runTest {
        val pdf = PlannedFile("12.pdf", FileSource.Whole(FileId("12")), "v1", 10, true, FileKind.Pdf)
        val mobi = PlannedFile("14.mobi", FileSource.Whole(FileId("14")), "v1", 10, true, FileKind.Book)
        keep(Book7, record(DownloadFormat.Text, complete, pdf))
        keep(Book8, record(DownloadFormat.Text, complete, mobi))

        assertThat(downloaded.text(Book7, FileId("12"))).isEqualTo(File(files.bookDir(Book7), "text/12.pdf"))
        assertThat(downloaded.text(Book8, FileId("14"))).isEqualTo(File(files.bookDir(Book8), "text/14.mobi"))
        assertThat(downloaded.text(Book7, FileId("14"))).isNull()
        assertThat(downloaded.text(Book8, FileId("12"))).isNull()
    }

    @Test
    fun `the text is found by the file it is drawn from, not by what its name starts with`() = runTest {
        val text = PlannedFile("book.epub", FileSource.Whole(FileId("12")), "v1", 10, true, FileKind.Epub)
        val other = PlannedFile("12.pdf", FileSource.Whole(FileId("14")), "v1", 10, true, FileKind.Pdf)
        keep(Book7, record(DownloadFormat.Text, complete, text))
        keep(Book8, record(DownloadFormat.Text, complete, other))

        assertThat(downloaded.text(Book7, FileId("12")))
            .isEqualTo(files.file(Book7, DownloadFormat.Text, "book.epub"))
        assertThat(downloaded.text(Book8, FileId("12"))).isNull()
        assertThat(downloaded.text(Book8, FileId("14"))).isEqualTo(File(files.bookDir(Book8), "text/12.pdf"))
    }

    @Test
    fun `an audio file whose name starts with the number of a file is not the text`() = runTest {
        keep(Book7, record(DownloadFormat.Audiobook, complete, audio("12.mp3", "https://books.example/a")))

        assertThat(downloaded.text(Book7, FileId("12"))).isNull()
    }

    @Test
    fun `the EPUB of a complete narration is given too, even while the text is not complete`() = runTest {
        keep(
            Book7,
            record(DownloadFormat.Text, FormatState.Queued, epub(FileId("12"))),
            record(DownloadFormat.Narration, complete, epub(FileId("12")), audio("000-one.mp3", TrackUrl)),
        )

        assertThat(downloaded.text(Book7, FileId("12")))
            .isEqualTo(files.file(Book7, DownloadFormat.Narration, "12.epub"))
    }

    @Test
    fun `a format that is not complete, or is being removed, gives nothing`() = runTest {
        keep(Book7, record(DownloadFormat.Text, FormatState.Downloading(10, 10), epub(FileId("12"))))
        keep(Book8, record(DownloadFormat.Text, complete, epub(FileId("12")), removing = true))
        keep(
            Book9,
            record(DownloadFormat.Audiobook, complete, audio("0-a", "https://books.example/a"), removing = true),
        )
        keep(Book10, record(DownloadFormat.Audiobook, FormatState.Queued, audio("0-b", "https://books.example/b")))
        val downloaded = started()

        assertThat(downloaded.text(Book7, FileId("12"))).isNull()
        assertThat(downloaded.text(Book8, FileId("12"))).isNull()
        assertThat(downloaded.audio("https://books.example/a")).isNull()
        assertThat(downloaded.audio("https://books.example/b")).isNull()
    }

    @Test
    fun `a file missing on the device gives nothing`() = runTest {
        keep(
            Book7,
            record(DownloadFormat.Text, complete, epub(FileId("12"))),
            record(DownloadFormat.Audiobook, complete, audio("0-a", "https://books.example/a")),
            onDisk = false,
        )
        val downloaded = started()

        assertThat(downloaded.text(Book7, FileId("12"))).isNull()
        assertThat(downloaded.audio("https://books.example/a")).isNull()
    }

    @Test
    fun `the audio of the complete formats is found by its address`() = runTest {
        keep(
            Book7,
            record(DownloadFormat.Audiobook, complete, audio("0-a", "https://books.example/a")),
            record(DownloadFormat.Narration, complete, epub(FileId("12")), audio("000-one.mp3", TrackUrl)),
        )
        val downloaded = started()

        assertThat(downloaded.audio("https://books.example/a"))
            .isEqualTo(files.file(Book7, DownloadFormat.Audiobook, "0-a"))
        assertThat(downloaded.audio(TrackUrl))
            .isEqualTo(files.file(Book7, DownloadFormat.Narration, "000-one.mp3"))
        assertThat(downloaded.audio("https://books.example/other")).isNull()
        // An EPUB is not something to play.
        assertThat(downloaded.audio("https://books.example/files/12")).isNull()
    }

    @Test
    fun `the order of the query parameters of an address does not matter`() = runTest {
        keep(
            Book7,
            record(DownloadFormat.Audiobook, complete, audio("0-a", "https://books.example/a?fileId=12&part=2")),
        )
        val downloaded = started()

        val kept = files.file(Book7, DownloadFormat.Audiobook, "0-a")
        assertThat(downloaded.audio("https://books.example/a?part=2&fileId=12")).isEqualTo(kept)
        assertThat(downloaded.audio("https://books.example/a?fileId=12&part=2")).isEqualTo(kept)
        assertThat(downloaded.audio("https://books.example/a?fileId=12&part=3")).isNull()
        assertThat(downloaded.audio("https://books.example/a")).isNull()
    }

    @Test
    fun `the audio found follows the registry`() = runTest {
        val downloaded = started()
        assertThat(downloaded.audio("https://books.example/a")).isNull()

        keep(Book7, record(DownloadFormat.Audiobook, complete, audio("0-a", "https://books.example/a")))
        runCurrent()
        assertThat(downloaded.audio("https://books.example/a")).isNotNull()

        registry.update(Book7) { book -> book?.copy(formats = book.formats.map { it.copy(removing = true) }) }
        runCurrent()
        assertThat(downloaded.audio("https://books.example/a")).isNull()

        registry.update(Book7) { null }
        keep(Book8, record(DownloadFormat.Audiobook, complete, audio("0-b", "https://books.example/b")))
        runCurrent()
        assertThat(downloaded.audio("https://books.example/a")).isNull()
        assertThat(downloaded.audio("https://books.example/b")).isNotNull()
    }
}
