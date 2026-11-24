package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.deviceCopy
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadedFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TheBook = BookId("1")

/** A kept PDF is fetched, updated and removed as a kept EPUB is. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptPdfTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    private fun pdf(bytes: Long = 40, version: String = "v1") =
        PlannedFile("12.pdf", FileSource.Whole(FileId("12")), version, bytes, resumable = true, kind = FileKind.Pdf)

    /** Book 1 keeps its PDF and an audiobook file. */
    private suspend fun QueueFixture.kept() {
        serve(TheBook, Text, pdf())
        serve(TheBook, Audiobook, audio("a1"))
        queue.ask(TheBook, setOf(Text, Audiobook))
        runner.runUntilIdle()
    }

    private fun QueueFixture.keptPdf(): File = File(files.bookDir(TheBook), "text/12.pdf")

    /** The text of book 1 the downloads hand the readers: its file 12, as they keep it. */
    private suspend fun QueueFixture.keptText(): File? =
        RegistryDownloadedFiles(registry, files, locations).text(TheBook, FileId("12"))

    @Test
    fun `a PDF asked is kept whole in the text's folder, and is what the page reader is handed`() = queueTest {
        kept()

        assertThat(state(TheBook, Text)).isEqualTo(FormatState.Complete(40))
        assertThat(keptPdf().length()).isEqualTo(40)
        assertThat(keptText()).isEqualTo(keptPdf())
        assertThat(checkNotNull(registry.book(TheBook)).deviceCopy()?.formats)
            .containsExactly(Text, Audiobook).inOrder()
    }

    @Test
    fun `a newer PDF takes the place of the kept one`() = queueTest {
        kept()
        serve(TheBook, Text, pdf(bytes = 55, version = "v2"), version = "v2")
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(updateFetcher.fetched).containsExactly("1/Text/12.pdf")
        val record = record(TheBook, Text)
        assertThat(record.state).isEqualTo(FormatState.Complete(55))
        assertThat(record.version).isEqualTo("v2")
        assertThat(record.fileId).isEqualTo(FileId("12"))
        assertThat(record.update).isNull()
        assertThat(keptPdf().length()).isEqualTo(55)
        assertThat(besideNames(TheBook)).isEmpty()
    }

    @Test
    fun `while the PDF is open the newer one waits beside the kept copy, which is what is read`() = queueTest {
        kept()
        serve(TheBook, Text, pdf(bytes = 55, version = "v2"), version = "v2")
        remover.hold(TheBook)
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(beside(TheBook, Text, "12.pdf").length()).isEqualTo(55)
        assertThat(keptText()?.length()).isEqualTo(40)

        remover.release(TheBook)
        runner.runUntilIdle()
        assertThat(keptText()?.length()).isEqualTo(55)
    }

    @Test
    fun `a deleted PDF goes after the undo window, the audiobook staying`() = queueTest {
        kept()

        assertThat(remover.delete(TheBook, setOf(Text))).isEqualTo(DeleteResult.Deleted)

        assertThat(keptText()).isNull()
        assertThat(keptPdf().exists()).isTrue()
        it.advanceTimeBy(10_000)
        it.runCurrent()
        assertThat(keptPdf().exists()).isFalse()
        assertThat(file(TheBook, Audiobook, "a1").exists()).isTrue()
        assertThat(checkNotNull(registry.book(TheBook)).formats.map { record -> record.format })
            .containsExactly(Audiobook)
    }

    @Test
    fun `undoing brings the PDF back, and the last format takes the book`() = queueTest {
        kept()
        remover.delete(TheBook, setOf(Text))
        remover.undoDelete(TheBook)
        it.advanceTimeBy(10_000)
        it.runCurrent()
        assertThat(keptText()).isEqualTo(keptPdf())

        remover.delete(TheBook, setOf(Text, Audiobook))
        it.advanceTimeBy(10_000)
        it.runCurrent()

        assertThat(registry.book(TheBook)).isNull()
        assertThat(files.bookDir(TheBook).exists()).isFalse()
    }
}
