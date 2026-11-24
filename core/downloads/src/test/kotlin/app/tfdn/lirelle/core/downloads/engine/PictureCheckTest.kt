package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.FileKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val Filler = 40
private const val CutAt = 10

/** What the download takes for a page: every picture the page reader can draw, told by its first bytes. */
class PictureCheckTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(bytes: ByteArray): File = folder.newFile().also { it.writeBytes(bytes) }

    private fun isPage(start: ByteArray): Boolean = FileKind.Page.isKindOf(file(start + ByteArray(Filler)))

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /** The start of a file of the ISO family: the size of its first box, `ftyp`, then its brand. */
    private fun ftyp(brand: String): ByteArray = byteArrayOf(0, 0, 0, 0x1C) + ascii("ftyp$brand")

    @Test
    fun `a JPEG, a PNG, a GIF and a WebP are pages`() {
        assertThat(isPage(jpeg(12))).isTrue()
        assertThat(isPage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).isTrue()
        assertThat(isPage(ascii("GIF89a"))).isTrue()
        assertThat(isPage(ascii("RIFF") + ByteArray(4) + ascii("WEBP"))).isTrue()
    }

    @Test
    fun `a BMP is a page`() {
        assertThat(isPage(ascii("BM") + ByteArray(12))).isTrue()
    }

    @Test
    fun `an AVIF and a HEIF are pages, by any of their brands`() {
        listOf("avif", "avis", "heic", "heix", "hevc", "mif1", "msf1").forEach { brand ->
            assertThat(isPage(ftyp(brand))).isTrue()
        }
    }

    @Test
    fun `a file of the same family that is no picture is no page`() {
        assertThat(isPage(ftyp("isom"))).isFalse()
        assertThat(isPage(ftyp("M4A "))).isFalse()
        // The brand is read where it is, after the mark of the box.
        assertThat(isPage(ascii("avif") + ascii("ftyp") + ByteArray(4))).isFalse()
    }

    @Test
    fun `text, an empty file, a file cut before its brand and no file are no pages`() {
        assertThat(isPage(ascii("""{"error":"Not found"}"""))).isFalse()
        assertThat(FileKind.Page.isKindOf(file(ByteArray(0)))).isFalse()
        assertThat(FileKind.Page.isKindOf(file(ftyp("avif").copyOf(CutAt)))).isFalse()
        assertThat(FileKind.Page.isKindOf(File(folder.root, "absent"))).isFalse()
    }
}
