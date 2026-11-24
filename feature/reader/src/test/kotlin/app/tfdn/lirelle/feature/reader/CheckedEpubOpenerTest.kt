package app.tfdn.lirelle.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.reader.archive.EpubProblem
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

private const val Container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

/** The real checks, on the platform's XML parser. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CheckedEpubOpenerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val opener = CheckedEpubOpener(UnconfinedTestDispatcher())

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `an EPUB is opened as an archive`() = runTest {
        val file = zip("META-INF/container.xml" to Container, "OEBPS/content.opf" to "<package/>")

        val opening = opener.open(file) as EpubOpening.Opened

        opening.archive.use { assertThat(it.entries.keys).contains("OEBPS/content.opf") }
    }

    @Test
    fun `a file that is not a zip is refused without being opened`() = runTest {
        val file = folder.newFile().apply { writeText("<html>Not found</html>") }

        assertThat(opener.open(file)).isEqualTo(EpubOpening.Refused(EpubProblem.NotAnArchive))
    }

    @Test
    fun `a zip that is not an EPUB, and a protected EPUB, are refused`() = runTest {
        val plain = zip("a.txt" to "a")
        val protected = zip(
            "META-INF/container.xml" to Container,
            "OEBPS/content.opf" to "<package/>",
            "META-INF/rights.xml" to "<rights/>",
        )

        assertThat(opener.open(plain)).isEqualTo(EpubOpening.Refused(EpubProblem.NotAnEpub))
        assertThat(opener.open(protected)).isEqualTo(EpubOpening.Refused(EpubProblem.Protected))
    }
}
