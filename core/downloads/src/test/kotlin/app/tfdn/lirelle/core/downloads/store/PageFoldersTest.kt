package app.tfdn.lirelle.core.downloads.store

import app.tfdn.lirelle.core.downloads.engine.wholeFileOf
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TheBook = BookId("7")

/** Where the pages kept of a book are, and what goes with the text when it is deleted. */
class PageFoldersTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root by lazy { File(folder.root, "downloads") }
    private val files by lazy { DownloadFiles(root) }

    private fun File.fill(): File = apply {
        checkNotNull(parentFile).mkdirs()
        writeText("x")
    }

    @Test
    fun `a page is in the pages of its book, under its index`() {
        assertThat(files.file(TheBook, DownloadFormat.Text, "0")).isEqualTo(File(root, "7/pages/0"))
        assertThat(files.file(TheBook, DownloadFormat.Text, "147")).isEqualTo(File(root, "7/pages/147"))
        assertThat(files.part(TheBook, DownloadFormat.Text, "147")).isEqualTo(File(root, "7/pages/147.part"))
    }

    @Test
    fun `a page of an update waits in the pages of the update`() {
        assertThat(files.updateFile(TheBook, DownloadFormat.Text, "0")).isEqualTo(File(root, "7/update/pages/0"))
        assertThat(files.updatePart(TheBook, DownloadFormat.Text, "12")).isEqualTo(File(root, "7/update/pages/12.part"))
    }

    @Test
    fun `the other texts stay where they were`() {
        assertThat(files.file(TheBook, DownloadFormat.Text, "12.epub")).isEqualTo(File(root, "7/epub/12.epub"))
        assertThat(files.file(TheBook, DownloadFormat.Text, "12.pdf")).isEqualTo(File(root, "7/text/12.pdf"))
        assertThat(files.updateFile(TheBook, DownloadFormat.Text, "12.pdf"))
            .isEqualTo(File(root, "7/update/text/12.pdf"))
    }

    @Test
    fun `a file of an audiobook or a narration named by a number is not a page`() {
        assertThat(files.file(TheBook, DownloadFormat.Audiobook, "3")).isEqualTo(File(root, "7/audiobook/3"))
        assertThat(files.file(TheBook, DownloadFormat.Narration, "3")).isEqualTo(File(root, "7/narration/3"))
    }

    @Test
    fun `deleting the text takes its pages, kept and waiting, and leaves the other formats`() {
        val page = files.file(TheBook, DownloadFormat.Text, "0").fill()
        val part = files.part(TheBook, DownloadFormat.Text, "1").fill()
        val waiting = files.updateFile(TheBook, DownloadFormat.Text, "0").fill()
        val audio = files.file(TheBook, DownloadFormat.Audiobook, "1.mp3").fill()
        val kept = files.kept(TheBook, "detail.json").fill()

        files.delete(TheBook, DownloadFormat.Audiobook)
        assertThat(page.exists()).isTrue()
        audio.fill()

        files.delete(TheBook, DownloadFormat.Text)

        assertThat(page.exists()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(waiting.exists()).isFalse()
        assertThat(audio.exists()).isTrue()
        assertThat(kept.exists()).isTrue()
    }

    @Test
    fun `dropping what an update fetched leaves the kept pages`() {
        val page = files.file(TheBook, DownloadFormat.Text, "0").fill()
        val waiting = files.updateFile(TheBook, DownloadFormat.Text, "0").fill()

        files.deleteUpdate(TheBook, DownloadFormat.Text)

        assertThat(page.exists()).isTrue()
        assertThat(waiting.exists()).isFalse()
    }

    @Test
    fun `a page is drawn from no single file, and a text from the file its source names, whatever its name`() {
        val text = PlannedFile("12.epub", FileSource.Whole(FileId("12")), null, null, true, FileKind.Epub)
        val renamed = text.copy(name = "book.pdf", source = FileSource.Whole(FileId("x.12")), kind = FileKind.Pdf)
        val audioless = text.copy(source = FileSource.Audioless(FileId("12")))
        val page = PlannedFile(
            "12", FileSource.Page(FileId("12"), PagedKind.Comic, 12), null, null, false, FileKind.Page,
        )
        val track = PlannedFile(
            "000-a.mp3", FileSource.ReadAlongTrack(FileId("12"), "a.mp3"), null, null, true, FileKind.Audio,
        )

        assertThat(wholeFileOf(text)).isEqualTo(FileId("12"))
        assertThat(wholeFileOf(renamed)).isEqualTo(FileId("x.12"))
        assertThat(wholeFileOf(audioless)).isEqualTo(FileId("12"))
        assertThat(wholeFileOf(page)).isNull()
        assertThat(wholeFileOf(track)).isNull()
    }
}
