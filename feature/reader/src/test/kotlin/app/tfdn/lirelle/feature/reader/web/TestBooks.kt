package app.tfdn.lirelle.feature.reader.web

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.tfdn.lirelle.core.reader.archive.EpubArchive
import app.tfdn.lirelle.feature.reader.BookContent
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A zip with these entries, as an archive the host can serve. */
fun testArchive(file: File, vararg entries: Pair<String, String>): EpubArchive {
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return EpubArchive(file)
}

/** An EPUB of one small entry, as the page is given it. */
fun testBook(file: File): BookContent.Epub = BookContent.Epub(testArchive(file, "a.txt" to "a"))

internal fun WebResourceResponse.text(): String = data.use { it.readBytes().decodeToString() }

internal fun WebResourceResponse.header(name: String): String? = responseHeaders?.get(name)

internal class FakeRequest(
    private val address: String,
    private val verb: String = "GET",
    private val mainFrame: Boolean = false,
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(address)
    override fun isForMainFrame(): Boolean = mainFrame
    override fun isRedirect(): Boolean = false
    override fun hasGesture(): Boolean = false
    override fun getMethod(): String = verb
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
}
