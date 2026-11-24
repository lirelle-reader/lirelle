package app.tfdn.lirelle.core.downloads.store

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TheBook = BookId("7")
private val OtherBook = BookId("8")

class DownloadFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root by lazy { File(folder.root, "downloads") }
    private val files by lazy { DownloadFiles(root) }

    private fun File.fill(): File = apply {
        checkNotNull(parentFile).mkdirs()
        writeText("x")
    }

    private fun recorded(format: DownloadFormat, vararg names: String) = FormatRecord(
        format = format,
        state = FormatState.Queued,
        files = names.map { PlannedFile(it, FileSource.AudioAsset(it), null, null, true, kindOf(it)) },
        fileId = null,
        version = null,
        askedAt = Instant.EPOCH,
    )

    private fun kindOf(name: String) = if (name.endsWith(".epub")) FileKind.Epub else FileKind.Audio

    @Test
    fun `every path of a book is under its own directory`() {
        val bookDir = files.bookDir(TheBook)

        assertThat(bookDir).isEqualTo(File(root, "7"))
        val paths = listOf(
            files.file(TheBook, DownloadFormat.Text, "12.epub"),
            files.file(TheBook, DownloadFormat.Audiobook, "1.mp3"),
            files.part(TheBook, DownloadFormat.Audiobook, "1.mp3"),
            files.kept(TheBook, "detail.json"),
        )
        paths.forEach { assertThat(it.startsWith(bookDir)).isTrue() }
        assertThat(paths.toSet()).hasSize(paths.size)
        assertThat(files.file(OtherBook, DownloadFormat.Text, "12.epub")).isNotEqualTo(paths.first())
    }

    @Test
    fun `the text and the narration share one path for the same EPUB`() {
        assertThat(files.file(TheBook, DownloadFormat.Narration, "12.epub"))
            .isEqualTo(files.file(TheBook, DownloadFormat.Text, "12.epub"))
        assertThat(files.part(TheBook, DownloadFormat.Narration, "12.epub"))
            .isEqualTo(files.part(TheBook, DownloadFormat.Text, "12.epub"))
        assertThat(files.file(TheBook, DownloadFormat.Narration, "13.epub"))
            .isNotEqualTo(files.file(TheBook, DownloadFormat.Text, "12.epub"))
    }

    @Test
    fun `the other files of two formats never share a path`() {
        assertThat(files.file(TheBook, DownloadFormat.Narration, "1.mp3"))
            .isNotEqualTo(files.file(TheBook, DownloadFormat.Audiobook, "1.mp3"))
    }

    @Test
    fun `a part is beside its file, under another name`() {
        val file = files.file(TheBook, DownloadFormat.Audiobook, "1.mp3")
        val part = files.part(TheBook, DownloadFormat.Audiobook, "1.mp3")

        assertThat(part.parentFile).isEqualTo(file.parentFile)
        assertThat(part).isNotEqualTo(file)
    }

    @Test
    fun `deleting the text leaves the EPUB the recorded narration uses`() {
        val epub = files.file(TheBook, DownloadFormat.Text, "12.epub").fill()
        val track = files.file(TheBook, DownloadFormat.Narration, "1.mp3").fill()

        files.delete(TheBook, DownloadFormat.Text, listOf(recorded(DownloadFormat.Narration, "12.epub", "1.mp3")))

        assertThat(epub.exists()).isTrue()
        assertThat(track.exists()).isTrue()
    }

    @Test
    fun `deleting the narration leaves what the text has received of the EPUB they share`() {
        val part = files.part(TheBook, DownloadFormat.Text, "12.epub").fill()
        val version = File(part.path + ".version").fill()
        val other = File(files.part(TheBook, DownloadFormat.Narration, "13.epub").path + ".version").fill()

        files.delete(TheBook, DownloadFormat.Narration, listOf(recorded(DownloadFormat.Text, "12.epub")))

        assertThat(part.exists()).isTrue()
        assertThat(version.exists()).isTrue()
        assertThat(other.exists()).isFalse()
    }

    @Test
    fun `deleting the text removes its EPUB when the narration uses another, or is not recorded`() {
        val text = files.file(TheBook, DownloadFormat.Text, "12.epub").fill()
        val part = files.part(TheBook, DownloadFormat.Text, "12.epub").fill()
        val narration = files.file(TheBook, DownloadFormat.Narration, "13.epub").fill()

        files.delete(TheBook, DownloadFormat.Text, listOf(recorded(DownloadFormat.Narration, "13.epub")))

        assertThat(text.exists()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(narration.exists()).isTrue()

        text.fill()
        files.delete(TheBook, DownloadFormat.Text)
        assertThat(text.exists()).isFalse()
    }

    @Test
    fun `deleting the narration removes its tracks and leaves the EPUB the recorded text uses`() {
        val epub = files.file(TheBook, DownloadFormat.Narration, "12.epub").fill()
        val track = files.file(TheBook, DownloadFormat.Narration, "1.mp3").fill()
        val audiobook = files.file(TheBook, DownloadFormat.Audiobook, "1.mp3").fill()

        files.delete(TheBook, DownloadFormat.Narration, listOf(recorded(DownloadFormat.Text, "12.epub")))

        assertThat(epub.exists()).isTrue()
        assertThat(track.exists()).isFalse()
        assertThat(audiobook.exists()).isTrue()
    }

    @Test
    fun `an audiobook recorded with a file of the same name does not hold an EPUB`() {
        val epub = files.file(TheBook, DownloadFormat.Text, "12.epub").fill()

        files.delete(TheBook, DownloadFormat.Text, listOf(recorded(DownloadFormat.Audiobook, "12.epub")))

        assertThat(epub.exists()).isFalse()
    }

    @Test
    fun `deleting a format leaves the kept data and the other books`() {
        files.file(TheBook, DownloadFormat.Audiobook, "1.mp3").fill()
        val kept = files.kept(TheBook, "detail.json").fill()
        val other = files.file(OtherBook, DownloadFormat.Audiobook, "1.mp3").fill()

        files.delete(TheBook, DownloadFormat.Audiobook)

        assertThat(files.file(TheBook, DownloadFormat.Audiobook, "1.mp3").exists()).isFalse()
        assertThat(kept.exists()).isTrue()
        assertThat(other.exists()).isTrue()
    }

    @Test
    fun `deleting a book removes its directory only`() {
        files.file(TheBook, DownloadFormat.Text, "12.epub").fill()
        files.kept(TheBook, "cover").fill()
        val other = files.kept(OtherBook, "cover").fill()

        files.deleteBook(TheBook)

        assertThat(files.bookDir(TheBook).exists()).isFalse()
        assertThat(other.exists()).isTrue()
    }

    @Test
    fun `deleting everything leaves no file under the root`() {
        files.file(TheBook, DownloadFormat.Text, "12.epub").fill()
        files.part(TheBook, DownloadFormat.Audiobook, "1.mp3").fill()
        files.kept(OtherBook, "detail.json").fill()

        files.deleteAll()

        assertThat(root.walkTopDown().filter { it.isFile }.toList()).isEmpty()
        assertThat(root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `deleting what is not there does nothing`() {
        files.delete(TheBook, DownloadFormat.Text)
        files.deleteBook(TheBook)
        files.deleteAll()
    }

    @Test
    fun `the free room is known before anything was written`() {
        assertThat(root.exists()).isFalse()

        assertThat(files.freeBytes()).isGreaterThan(0)
    }

    @Test
    fun `the files of an update wait under the book, beside the kept ones and never over them`() {
        val waiting = files.updateFile(TheBook, DownloadFormat.Audiobook, "1.mp3")
        val part = files.updatePart(TheBook, DownloadFormat.Audiobook, "1.mp3")

        assertThat(waiting.startsWith(files.bookDir(TheBook))).isTrue()
        assertThat(waiting).isNotEqualTo(files.file(TheBook, DownloadFormat.Audiobook, "1.mp3"))
        assertThat(waiting).isEqualTo(files.updates.file(TheBook, DownloadFormat.Audiobook, "1.mp3"))
        assertThat(part.parentFile).isEqualTo(waiting.parentFile)
        assertThat(part).isNotEqualTo(waiting)
        assertThat(part).isNotEqualTo(files.part(TheBook, DownloadFormat.Audiobook, "1.mp3"))
        assertThat(part).isEqualTo(files.updates.part(TheBook, DownloadFormat.Audiobook, "1.mp3"))
    }

    @Test
    fun `the EPUB the text and the narration share waits once for both`() {
        assertThat(files.updateFile(TheBook, DownloadFormat.Narration, "12.epub"))
            .isEqualTo(files.updateFile(TheBook, DownloadFormat.Text, "12.epub"))
        assertThat(files.updateFile(TheBook, DownloadFormat.Text, "12.epub"))
            .isNotEqualTo(files.file(TheBook, DownloadFormat.Text, "12.epub"))
        assertThat(files.updateFile(TheBook, DownloadFormat.Narration, "1.mp3"))
            .isNotEqualTo(files.updateFile(TheBook, DownloadFormat.Audiobook, "1.mp3"))
    }

    @Test
    fun `dropping what an update fetched leaves the kept files`() {
        val kept = files.file(TheBook, DownloadFormat.Audiobook, "1.mp3").fill()
        val waiting = files.updateFile(TheBook, DownloadFormat.Audiobook, "1.mp3").fill()
        val part = files.updatePart(TheBook, DownloadFormat.Audiobook, "2.mp3").fill()

        files.deleteUpdate(TheBook, DownloadFormat.Audiobook)

        assertThat(kept.exists()).isTrue()
        assertThat(waiting.exists()).isFalse()
        assertThat(part.exists()).isFalse()
    }

    @Test
    fun `deleting a format takes what its update fetched, but for the EPUB another update waits for`() {
        val track = files.updateFile(TheBook, DownloadFormat.Narration, "1.mp3").fill()
        val epub = files.updateFile(TheBook, DownloadFormat.Narration, "12.epub").fill()
        val text = recorded(DownloadFormat.Text, "12.epub").let { record ->
            record.copy(update = FormatUpdate("v2", record.files, bytes = 1, state = FormatState.Queued))
        }

        files.delete(TheBook, DownloadFormat.Narration, listOf(text))
        assertThat(track.exists()).isFalse()
        assertThat(epub.exists()).isTrue()

        files.delete(TheBook, DownloadFormat.Text)
        assertThat(epub.exists()).isFalse()
    }
}
