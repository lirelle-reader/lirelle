package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.archive.EpubProblem
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.feature.reader.bridge.BookFormat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A book whose file to read is a Kindle book or a FictionBook: the file goes to the page whole, as the cache
 * has it, and nothing of what is checked in an EPUB is asked of it.
 */
class BookOpenerFormatsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = CachedBookKey(fileId = FileId("7"), contentVersion = "v1")

    private fun file(format: String) = BookFile(
        id = FileId("7"),
        kind = BookFileKind.Text,
        format = format,
        sizeBytes = 900_000,
        isPrimary = true,
        narrationSeconds = null,
        chapterCount = null,
        contentVersion = "v1",
    )

    /** The sample book with one file of [format], and an opener of EPUBs that refuses whatever it is given. */
    private fun TestScope.harness(format: String) = ReaderHarness(this, folder.newFolder()).apply {
        detail = detail.copy(files = listOf(file(format)))
        library.bookResult = ApiResult.Success(detail)
        epub = EpubOpening.Refused(EpubProblem.NotAnArchive)
    }

    private suspend fun ReaderHarness.open(epubs: EpubOpener = EpubOpener { epub }): OpeningStep =
        BookOpener(library, cache, epubs, { webViewVersion }, kept, downloads).open(BookId("12")).toList().last()

    @Test
    fun `a Kindle book and a FictionBook are handed over whole, each in the format the page opens it with`() =
        runTest {
            val formats = mapOf(
                "MOBI" to BookFormat.Mobi,
                "AZW" to BookFormat.Mobi,
                "AZW3" to BookFormat.Mobi,
                "azw3" to BookFormat.Mobi,
                "FB2" to BookFormat.Fb2,
            )
            formats.forEach { (format, expected) ->
                val harness = harness(format)

                val book = (harness.open() as OpeningStep.Ready).book

                assertThat(book.content).isEqualTo(BookContent.Whole(harness.bookFile, expected))
                assertThat(book.file.format).isEqualTo(format)
            }
        }

    @Test
    fun `nothing of an EPUB is checked in such a file`() = runTest {
        val harness = harness("MOBI")
        var asked = 0

        val step = harness.open {
            asked++
            harness.epub
        }

        assertThat(step).isInstanceOf(OpeningStep.Ready::class.java)
        assertThat(asked).isEqualTo(0)
        assertThat(harness.cache.discarded).isEmpty()
    }

    @Test
    fun `the file is fetched whole through the cache, and held until the book is closed`() = runTest {
        val harness = harness("FB2")

        val book = (harness.open() as OpeningStep.Ready).book

        assertThat(harness.cache.opened).containsExactly(key to false)
        assertThat(harness.cache.held).containsExactly(key)
        assertThat(harness.cache.released).isEmpty()

        book.close()
        book.close()

        assertThat(harness.cache.released).containsExactly(key)
    }

    @Test
    fun `such a book has no narration to follow`() = runTest {
        listOf("MOBI", "AZW3", "FB2").forEach { format ->
            val book = (harness(format).open() as OpeningStep.Ready).book

            assertThat(book.file.hasReadAlong).isFalse()
        }
    }

    @Test
    fun `an EPUB and a KEPUB are still checked and opened as archives`() = runTest {
        listOf("EPUB", "KEPUB").forEach { format ->
            val refused = harness(format)
            assertThat(refused.open()).isEqualTo(OpeningStep.Failed(ReaderFailure.NotAnEpub))

            val fine = harness(format)
            fine.epub = EpubOpening.Opened(fine.archive)
            val book = (fine.open() as OpeningStep.Ready).book
            assertThat(book.content).isEqualTo(BookContent.Epub(fine.archive))
            assertThat(book.content.format).isEqualTo(BookFormat.Epub)
            book.close()
            assertThat(fine.archive.open("a.txt")).isNull()
        }
    }

    @Test
    fun `a book with nothing the reader lays out has no file to open`() = runTest {
        val harness = harness("PDF")

        assertThat(harness.open()).isEqualTo(OpeningStep.Failed(ReaderFailure.NoEpub))
        assertThat(harness.cache.opened).isEmpty()
    }
}
