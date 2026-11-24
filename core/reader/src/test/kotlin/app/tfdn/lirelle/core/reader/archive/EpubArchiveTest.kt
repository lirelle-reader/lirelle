package app.tfdn.lirelle.core.reader.archive

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun archive(vararg more: Pair<String, String>) = EpubArchive(epub(folder.newFile("book.epub"), *more))

    @Test
    fun `entries are listed with their size, directories left out`() {
        archive("OEBPS/" to "", "OEBPS/text/" to "", "OEBPS/text/ch1.xhtml" to "<html>one</html>").use { book ->
            assertThat(book.entries).containsExactly(
                "mimetype", "application/epub+zip".length.toLong(),
                ContainerPath, Container.toByteArray().size.toLong(),
                "OEBPS/content.opf", "<package/>".length.toLong(),
                "OEBPS/text/ch1.xhtml", "<html>one</html>".length.toLong(),
            )
        }
    }

    @Test
    fun `an entry is read from the archive`() {
        archive("OEBPS/text/ch1.xhtml" to "<html>one</html>").use { book ->
            val text = book.open("OEBPS/text/ch1.xhtml")?.use { it.readBytes().decodeToString() }

            assertThat(text).isEqualTo("<html>one</html>")
        }
    }

    @Test
    fun `an entry that does not exist, or a directory, is null`() {
        archive("OEBPS/text/" to "").use { book ->
            assertThat(book.open("OEBPS/missing.xhtml")).isNull()
            assertThat(book.open("OEBPS/text/")).isNull()
            assertThat(book.open("OEBPS/text")).isNull()
            assertThat(book.open("")).isNull()
            assertThat(book.open("oebps/content.opf")).isNull()
        }
    }

    @Test
    fun `a path that leaves the archive is refused, even when a file is there`() {
        File(folder.root, "secret.txt").writeText("outside")
        val absolute = folder.newFile("absolute.txt").apply { writeText("outside") }.absolutePath

        archive().use { book ->
            val paths = listOf(
                "../secret.txt",
                "OEBPS/../../secret.txt",
                "./../secret.txt",
                "..\\secret.txt",
                absolute,
                "/etc/hosts",
                "file://$absolute",
            )
            paths.forEach { assertThat(book.open(it)).isNull() }
        }
    }

    /** A zip may name its entries as it likes: such names are neither listed nor served. */
    @Test
    fun `entries whose name leaves the archive are ignored`() {
        val odd = listOf("../up.txt", "/absolute.txt", "OEBPS/../../up.txt", "OEBPS/./dot.txt", "a\\b.txt", "OEBPS//x")

        archive(*odd.map { it to "trap" }.toTypedArray()).use { book ->
            assertThat(book.entries.keys).containsNoneIn(odd)
            odd.forEach { assertThat(book.open(it)).isNull() }
            assertThat(book.open("OEBPS/up.txt")).isNull()
            assertThat(book.open("up.txt")).isNull()
        }
        assertThat(folder.root.list()).asList().containsExactly("book.epub")
    }

    @Test
    fun `after closing, entries are no longer served`() {
        val book = archive()
        book.close()

        assertThat(book.open("OEBPS/content.opf")).isNull()
    }

    @Test
    fun `a file that is not a zip cannot be opened as an archive`() {
        val text = folder.newFile().apply { writeText("not a zip") }

        assertThrows(IOException::class.java) { EpubArchive(text) }
    }

    @Test
    fun `an archive with more entries than any book has cannot be opened`() {
        EpubArchive(epubOfEntries(folder.newFile(), MaxArchiveEntries)).use { book ->
            assertThat(book.entries).hasSize(MaxArchiveEntries)
        }

        val tooMany = epubOfEntries(folder.newFile(), MaxArchiveEntries + 1)

        assertThrows(IOException::class.java) { EpubArchive(tooMany) }
        // Nothing keeps the file open: on a device, a refused book is deleted next.
        assertThat(tooMany.delete()).isTrue()
    }

    @Test
    fun `an entry that declares an absurd size is not served`() {
        val file = epub(folder.newFile("book.epub"), "OEBPS/bomb.xhtml" to "<html>small</html>")
        declareSize(file, "OEBPS/bomb.xhtml", MaxEntryBytes + 1)

        EpubArchive(file).use { book ->
            assertThat(book.entries["OEBPS/bomb.xhtml"]).isEqualTo(MaxEntryBytes + 1)
            assertThat(book.open("OEBPS/bomb.xhtml")).isNull()
            assertThat(book.open("OEBPS/content.opf")).isNotNull()
        }
    }

    @Test
    fun `an entry that holds more than it declares, and more than the bound, is cut short`() {
        val text = "<html>" + "a".repeat(1_000) + "</html>"
        val file = epub(folder.newFile("book.epub"), "OEBPS/liar.xhtml" to text, "OEBPS/honest.xhtml" to "<html/>")
        declareSize(file, "OEBPS/liar.xhtml", 7)

        EpubArchive(file, maxEntryBytes = 100).use { book ->
            assertThrows(IOException::class.java) { book.open("OEBPS/liar.xhtml")?.use { it.readBytes() } }
            assertThat(book.open("OEBPS/honest.xhtml")?.use { it.readBytes().decodeToString() }).isEqualTo("<html/>")
        }
    }

    @Test
    fun `media types follow the extension, whatever its case`() {
        archive().use { book ->
            val expected = mapOf(
                "a/reader.js" to "text/javascript",
                "a/module.mjs" to "text/javascript",
                "a/style.CSS" to "text/css",
                "a/ch1.xhtml" to "application/xhtml+xml",
                "a/ch1.html" to "text/html",
                "a/ch1.htm" to "text/html",
                "a/cover.svg" to "image/svg+xml",
                "a/font.ttf" to "font/ttf",
                "a/font.otf" to "font/otf",
                "a/font.woff" to "font/woff",
                "a/font.woff2" to "font/woff2",
                "a/cover.jpg" to "image/jpeg",
                "a/cover.JPEG" to "image/jpeg",
                "a/cover.png" to "image/png",
                "a/cover.gif" to "image/gif",
                "a/cover.webp" to "image/webp",
                "a/content.opf" to "application/oebps-package+xml",
                "a/toc.ncx" to "application/x-dtbncx+xml",
                "a/container.xml" to "application/xml",
                "a/data.bin" to "application/octet-stream",
                "a/archive.tar.gz" to "application/octet-stream",
                "mimetype" to "application/octet-stream",
                "a.dir/noextension" to "application/octet-stream",
                "" to "application/octet-stream",
            )

            expected.forEach { (path, type) -> assertThat(book.mediaType(path)).isEqualTo(type) }
        }
    }
}
