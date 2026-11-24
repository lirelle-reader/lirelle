package app.tfdn.lirelle.core.comics.pdf

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private const val ServerVersion = "B"

/**
 * The pages drawn are kept under the document they were drawn from. The copy the device keeps of a book is
 * opened whatever the version the server has: its pages are its own, not those of the server's version.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfKeptVersionTest {

    private val bench = PdfBench()
    private val documents = bench.documents
    private val copy = File.createTempFile("kept", ".pdf")

    @After
    fun tearDown() {
        bench.close()
        copy.delete()
    }

    /** The device keeps the book's file, as it was [at] a moment. */
    private fun keep(at: Long) {
        copy.writeBytes(fixture("three-pages.pdf"))
        copy.setLastModified(at)
        bench.kept = copy
    }

    /** Opens the document as the server has it now, draws its first page and leaves: the folder the page is in. */
    private fun folderOfFirstPage(): String = runBlocking {
        val pages = bench.open(version = ServerVersion)
        pages.pageCount()
        pages.show(0)
        val folder = pages.drawnAt(0, FirstLongSidePx).file.parentFile.name
        pages.close()
        awaitThat { documents.closed.get() == documents.opened.size }
        folder
    }

    @Test
    fun `the pages of a kept copy are not filed under the version the server has`() {
        keep(at = 1_000_000)

        val folder = folderOfFirstPage()

        assertThat(folder).isNotEqualTo("$PdfFileId-$ServerVersion")
        assertThat(folder).startsWith("$PdfFileId-")
        assertThat(bench.albums()).containsExactly(folder)
    }

    @Test
    fun `the same kept copy opened again finds its pages`() {
        keep(at = 1_000_000)
        val first = folderOfFirstPage()

        val again = folderOfFirstPage()

        assertThat(again).isEqualTo(first)
        assertThat(documents.drawn).hasSize(1)
    }

    @Test
    fun `once the kept copy is replaced its pages are drawn afresh, and those of the old one go`() {
        keep(at = 1_000_000)
        val old = folderOfFirstPage()

        keep(at = 2_000_000)
        val new = folderOfFirstPage()

        assertThat(new).isNotEqualTo(old)
        assertThat(bench.albums()).containsExactly(new)
        assertThat(documents.drawn).hasSize(2)
    }

    @Test
    fun `without the kept copy the pages are those of the version fetched, and those of the copy go`() {
        keep(at = 1_000_000)
        folderOfFirstPage()

        bench.kept = null
        val fetched = folderOfFirstPage()

        assertThat(fetched).isEqualTo("$PdfFileId-$ServerVersion")
        assertThat(bench.albums()).containsExactly(fetched)
    }
}
