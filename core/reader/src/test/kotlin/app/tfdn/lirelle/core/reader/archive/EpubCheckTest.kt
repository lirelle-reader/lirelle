package app.tfdn.lirelle.core.reader.archive

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Run on the platform's XML parser: what it does with a doctype is what these tests are about. */
@RunWith(RobolectricTestRunner::class)
class EpubCheckTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val ok = EpubCheckResult.Ok
    private val protected = EpubCheckResult.Refused(EpubProblem.Protected)
    private val notAnEpub = EpubCheckResult.Refused(EpubProblem.NotAnEpub)

    private fun book(vararg more: Pair<String, String>): File = epub(folder.newFile(), *more)

    @Test
    fun `a plain EPUB is accepted`() {
        assertThat(checkEpub(book())).isEqualTo(ok)
    }

    @Test
    fun `a file that is not a zip is not an archive`() {
        val text = folder.newFile().apply { writeText("<html><body>Signed out</body></html>") }
        val empty = folder.newFile()
        val missing = File(folder.root, "missing.epub")

        listOf(text, empty, missing).forEach {
            assertThat(checkEpub(it)).isEqualTo(EpubCheckResult.Refused(EpubProblem.NotAnArchive))
        }
    }

    @Test
    fun `an archive with more entries than any book has is not an EPUB`() {
        assertThat(checkEpub(epubOfEntries(folder.newFile(), MaxArchiveEntries))).isEqualTo(ok)
        assertThat(checkEpub(epubOfEntries(folder.newFile(), MaxArchiveEntries + 1))).isEqualTo(notAnEpub)
    }

    @Test
    fun `a zip without a container is not an EPUB`() {
        assertThat(checkEpub(zip(folder.newFile(), "notes.txt" to "hello"))).isEqualTo(notAnEpub)
    }

    @Test
    fun `a container that names no package of the archive is not an EPUB`() {
        val noRootfile = """<container version="1.0"><rootfiles/></container>"""
        val elsewhere = Container.replace("OEBPS/content.opf", "OEBPS/other.opf")

        assertThat(checkEpub(book(ContainerPath to noRootfile))).isEqualTo(notAnEpub)
        assertThat(checkEpub(book(ContainerPath to elsewhere))).isEqualTo(notAnEpub)
        assertThat(checkEpub(book(ContainerPath to "not xml at all"))).isEqualTo(notAnEpub)
    }

    @Test
    fun `fonts that are only obfuscated do not make a book protected`() {
        val both = encryption(IdpfObfuscation, AdobeObfuscation)

        assertThat(checkEpub(book(EncryptionPath to both))).isEqualTo(ok)
        assertThat(checkEpub(book(EncryptionPath to encryption()))).isEqualTo(ok)
    }

    @Test
    fun `any other algorithm makes the book protected`() {
        val aes = "http://www.w3.org/2001/04/xmlenc#aes256-cbc"

        assertThat(checkEpub(book(EncryptionPath to encryption(aes)))).isEqualTo(protected)
        assertThat(checkEpub(book(EncryptionPath to encryption(IdpfObfuscation, aes)))).isEqualTo(protected)
        assertThat(checkEpub(book(EncryptionPath to encryption("")))).isEqualTo(protected)
    }

    @Test
    fun `an encryption file that cannot be read makes the book protected`() {
        assertThat(checkEpub(book(EncryptionPath to "<encryption><unclosed></encryption>"))).isEqualTo(protected)
        assertThat(checkEpub(book(EncryptionPath to "x".repeat(TooLarge)))).isEqualTo(protected)
    }

    @Test
    fun `a rights file or a licence makes the book protected`() {
        assertThat(checkEpub(book("META-INF/rights.xml" to "<rights/>"))).isEqualTo(protected)
        assertThat(checkEpub(book("META-INF/license.lcpl" to "{}"))).isEqualTo(protected)
    }

    private fun withDoctype(doctype: String) = Container.replace("<container", "$doctype<container")

    /** Neither file has a use for a doctype, and the platform's parser would obey its entities. */
    @Test
    fun `a file with a doctype is not read`() {
        val doctype = """<!DOCTYPE encryption [<!ELEMENT encryption ANY>]>"""

        assertThat(checkEpub(book(EncryptionPath to encryption(IdpfObfuscation, prolog = doctype))))
            .isEqualTo(protected)
        assertThat(checkEpub(book(ContainerPath to withDoctype(doctype)))).isEqualTo(notAnEpub)
    }

    /** What an entity stands for cannot pass for an accepted value. */
    @Test
    fun `an entity declared in the file is not expanded`() {
        val doctype = """<!DOCTYPE encryption [<!ENTITY alg "$IdpfObfuscation">]>"""

        assertThat(checkEpub(book(EncryptionPath to encryption("&alg;", prolog = doctype)))).isEqualTo(protected)
        assertThat(checkEpub(book(EncryptionPath to encryption("&alg;")))).isEqualTo(protected)
    }

    @Test(timeout = 5_000)
    fun `entities that would expand to gigabytes cost nothing`() {
        val levels = (1..9).joinToString("") { level ->
            """<!ENTITY lol$level "${"&lol${level - 1};".repeat(10)}">"""
        }
        val doctype = """<!DOCTYPE container [<!ENTITY lol0 "lol">$levels]>"""
        val inText = withDoctype(doctype).replace("<rootfiles>", "<rootfiles>&lol9;")
        val inAttribute = withDoctype(doctype).replace("OEBPS/content.opf", "&lol9;")

        assertThat(checkEpub(book(ContainerPath to withDoctype(doctype)))).isEqualTo(notAnEpub)
        assertThat(checkEpub(book(ContainerPath to inText))).isEqualTo(notAnEpub)
        assertThat(checkEpub(book(ContainerPath to inAttribute))).isEqualTo(notAnEpub)
        assertThat(checkEpub(book(EncryptionPath to encryption("&lol9;", prolog = doctype)))).isEqualTo(protected)
    }

    @Test
    fun `nothing outside the file is fetched, on the network or on the device`() {
        val secret = folder.newFile("secret.txt").apply { writeText(AdobeObfuscation) }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = ShortWait
            val url = "http://127.0.0.1:${server.localPort}/evil.dtd"
            val external = """<!DOCTYPE encryption SYSTEM "$url">"""
            val entities = """<!DOCTYPE encryption [
                <!ENTITY % remote SYSTEM "$url"> %remote;
                <!ENTITY net SYSTEM "$url">
                <!ENTITY local SYSTEM "${secret.toURI()}">]>"""

            listOf(external, entities).forEach { doctype ->
                assertThat(checkEpub(book(EncryptionPath to encryption(IdpfObfuscation, prolog = doctype))))
                    .isEqualTo(protected)
                assertThat(checkEpub(book(EncryptionPath to encryption("&net;", prolog = doctype))))
                    .isEqualTo(protected)
                assertThat(checkEpub(book(EncryptionPath to encryption("&local;", prolog = doctype))))
                    .isEqualTo(protected)
                assertThat(checkEpub(book(ContainerPath to withDoctype(doctype)))).isEqualTo(notAnEpub)
            }

            assertThrows(SocketTimeoutException::class.java) { server.accept() }
        }
    }

    private companion object {
        const val IdpfObfuscation = "http://www.idpf.org/2008/embedding"
        const val AdobeObfuscation = "http://ns.adobe.com/pdf/enc#RC"
        const val TooLarge = 2_000_000
        const val ShortWait = 300
    }
}
