package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** A text that is not an EPUB is fetched under its own name, and checked as what it is. */
class DownloaderTextTest {

    @get:Rule
    internal val fixture = DownloaderFixture()

    private val server get() = fixture.server
    private val text = DownloadFormat.Text
    private val pdfBytes = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".toByteArray()

    private fun planned(name: String, kind: FileKind, expectedBytes: Long? = null) = PlannedFile(
        name = name,
        source = FileSource.Whole(FileId("12")),
        version = "v1",
        expectedBytes = expectedBytes,
        resumable = true,
        kind = kind,
    )

    @Test
    fun `a PDF is kept under its own name, in the text's own folder`() = runBlocking {
        val file = planned("12.pdf", FileKind.Pdf)
        server.enqueue(whole(pdfBytes).build())

        val result = fixture.fetch(file, text)

        assertThat(result).isEqualTo(FetchResult.Done)
        val target = fixture.target(file, text)
        assertThat(target.readBytes()).isEqualTo(pdfBytes)
        assertThat(target).isEqualTo(File(fixture.files.bookDir(TheBook), "text/12.pdf"))
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/files/12")
    }

    @Test
    fun `a file that does not start as a PDF is refused and removed, as a file that is not an EPUB is`() =
        runBlocking {
            val file = planned("12.pdf", FileKind.Pdf)
            server.enqueue(whole("<html>Sign in</html>".toByteArray()).build())

            val result = fixture.fetch(file, text)

            assertThat(result).isEqualTo(FetchResult.Refused(DownloadFailure.NotAnEpub))
            assertThat(fixture.part(file, text).exists()).isFalse()
            assertThat(fixture.partVersion(file, text).exists()).isFalse()
            assertThat(fixture.target(file, text).exists()).isFalse()
        }

    @Test
    fun `an empty answer is no PDF`() = runBlocking {
        val file = planned("12.pdf", FileKind.Pdf)
        server.enqueue(whole(ByteArray(0)).build())

        assertThat(fixture.fetch(file, text)).isEqualTo(FetchResult.Refused(DownloadFailure.NotAnEpub))
        assertThat(fixture.target(file, text).exists()).isFalse()
    }

    @Test
    fun `a Kindle book and a FictionBook are kept as they come, under their own names`() = runBlocking {
        listOf("12.mobi", "12.azw", "12.azw3", "12.fb2").forEach { name ->
            val file = planned(name, FileKind.Book)
            val book = bytes(300)
            server.enqueue(whole(book).build())

            val result = fixture.fetch(file, text)

            assertThat(result).isEqualTo(FetchResult.Done)
            assertThat(fixture.target(file, text).readBytes()).isEqualTo(book)
            assertThat(fixture.target(file, text)).isEqualTo(File(fixture.files.bookDir(TheBook), "text/$name"))
        }
    }

    @Test
    fun `a PDF cut on the way is resumed, and checked once whole`() = runBlocking {
        val whole = pdfBytes + bytes(4_000)
        val file = planned("12.pdf", FileKind.Pdf, expectedBytes = whole.size.toLong())
        val served = RangedFile(whole, cuts = 1)
        server.dispatcher = served

        val first = fixture.fetch(file, text)
        val second = fixture.fetch(file, text)

        assertThat(first).isInstanceOf(FetchResult.Cut::class.java)
        assertThat(second).isEqualTo(FetchResult.Done)
        assertThat(fixture.target(file, text).readBytes()).isEqualTo(whole)
    }
}
