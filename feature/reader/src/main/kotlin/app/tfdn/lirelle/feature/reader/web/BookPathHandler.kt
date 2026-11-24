package app.tfdn.lirelle.feature.reader.web

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import app.tfdn.lirelle.core.reader.archive.EpubArchive
import app.tfdn.lirelle.feature.reader.BookContent
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val BookPrefix = "/book/"

private const val IndexName = "index.json"

/** Under the book's address, the one file of a book that is not an EPUB. */
private const val WholeName = "file"

/** 256 bits: the address of a book cannot be guessed by a page that was not given it. */
private const val TokenBytes = 32

/** A book and the token it is served under. */
internal class ServedBook(val token: String, val content: BookContent) {
    val base: String = "$BookPrefix$token/"

    /** `{"entries": {"<path>": <uncompressed size>}}` of an EPUB; null for a book that is one file. */
    val index: ByteArray? by lazy { (content as? BookContent.Epub)?.archive?.let(::indexOf) }
}

private fun indexOf(archive: EpubArchive): ByteArray = buildJsonObject {
    putJsonObject("entries") { archive.entries.forEach { (path, size) -> put(path, JsonPrimitive(size)) } }
}.toString().toByteArray()

/** The one book the web view may read at a time. The web view asks from its own threads. */
internal class BookServer {
    private val random = SecureRandom()
    private val served = MutableStateFlow<ServedBook?>(null)
    private val address = MutableStateFlow<String?>(null)

    /** `/book/<token>/` of the book being served. */
    val base: StateFlow<String?> = address.asStateFlow()

    fun current(): ServedBook? = served.value

    /** Serves [content] under a new token, or nothing. What was served before is gone at once; it is not closed. */
    fun serve(content: BookContent?) {
        val book = content?.let { ServedBook(newToken(), it) }
        served.value = book
        address.value = book?.base
    }

    private fun newToken(): String =
        ByteArray(TokenBytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}

/**
 * Serves the current book: `<token>/index.json` and `<token>/<entry>` of an EPUB, `<token>/file` of a book that
 * is one file. The path arrives decoded, and the rest of it after the token is taken as the exact name of an
 * entry: the archive only opens what it lists. The one file is read from the disk as the web view takes it: it
 * is never held whole here.
 */
internal class BookPathHandler(private val books: BookServer) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse {
        val book = books.current()
        val token = path.substringBefore('/')
        val entry = path.substringAfter('/', missingDelimiterValue = "")
        if (book == null || !sameToken(token, book.token)) return notFound()
        return when (val content = book.content) {
            is BookContent.Epub -> entryOf(content.archive, book.index, entry)
            is BookContent.Whole -> if (entry == WholeName) whole(content.file) else notFound()
        }
    }

    private fun entryOf(archive: EpubArchive, index: ByteArray?, entry: String): WebResourceResponse = when {
        entry == IndexName && index != null -> served(JsonMediaType, Utf8, ByteArrayInputStream(index))
        else -> archive.open(entry)?.let { served(archive.mediaType(entry), null, it) } ?: notFound()
    }

    /** A file that has gone from the cache is not found: the page then says it could not open the book. */
    private fun whole(file: File): WebResourceResponse = try {
        served(BinaryMediaType, null, FileInputStream(file))
    } catch (_: IOException) {
        notFound()
    }

    private fun sameToken(given: String, expected: String): Boolean =
        MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
}
