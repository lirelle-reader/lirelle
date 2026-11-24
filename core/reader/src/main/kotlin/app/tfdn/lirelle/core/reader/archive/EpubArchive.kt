package app.tfdn.lirelle.core.reader.archive

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

private const val Binary = "application/octet-stream"

/** More entries than any book has: the largest of those surveyed has a few hundred. */
const val MaxArchiveEntries = 50_000

/** The most one entry may hold once inflated. No page, picture or font of a book comes near it. */
const val MaxEntryBytes = 512L * 1024 * 1024

private val MediaTypes = mapOf(
    "js" to "text/javascript",
    "mjs" to "text/javascript",
    "css" to "text/css",
    "xhtml" to "application/xhtml+xml",
    "xht" to "application/xhtml+xml",
    "html" to "text/html",
    "htm" to "text/html",
    "svg" to "image/svg+xml",
    "ttf" to "font/ttf",
    "otf" to "font/otf",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "avif" to "image/avif",
    "opf" to "application/oebps-package+xml",
    "ncx" to "application/x-dtbncx+xml",
    "xml" to "application/xml",
)

/**
 * The entries of a book, served by name. Only what the archive lists under a plain relative name
 * can be opened: nothing here ever resolves a path against the file system.
 *
 * @param maxEntryBytes the most an entry may hold once inflated, see [MaxEntryBytes].
 * @throws IOException when [file] is not a zip archive, or lists more than [MaxArchiveEntries]
 *   entries; [checkEpub] says so without throwing.
 */
class EpubArchive(file: File, private val maxEntryBytes: Long = MaxEntryBytes) : Closeable {
    private val zip = ZipFile(file)

    /** Path in the archive -> uncompressed size, directories left out. */
    val entries: Map<String, Long> = runCatching { listed(zip) }.onFailure { zip.close() }.getOrThrow()

    /**
     * Null when there is no such entry, or when it declares more than the bound. The path is the
     * entry's exact name: it is not normalised. An entry that holds more than the bound, whatever
     * it declares, fails with an [IOException] when the reading gets there.
     */
    fun open(path: String): InputStream? {
        val size = entries[path]
        if (size == null || size > maxEntryBytes) return null
        return try {
            zip.getEntry(path)?.let { CappedInputStream(zip.getInputStream(it), maxEntryBytes) }
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            // Closed while the page was still asking.
            null
        }
    }

    /** From the extension; `application/octet-stream` for what a book has no business containing. */
    fun mediaType(path: String): String {
        val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
        return MediaTypes[extension.lowercase()] ?: Binary
    }

    override fun close() = zip.close()
}

private fun listed(zip: ZipFile): Map<String, Long> {
    if (zip.size() > MaxArchiveEntries) throw IOException("More entries than a book has")
    return zip.entries().asSequence()
        .filter { !it.isDirectory && isPlainPath(it.name) }
        .associate { it.name to it.size }
}

/** Fails once more than [limit] bytes were read: an archive can inflate to far more than it declares. */
private class CappedInputStream(stream: InputStream, private val limit: Long) : FilterInputStream(stream) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) counted(it) }

    private fun counted(bytes: Int) {
        count += bytes
        if (count > limit) throw IOException("An entry larger than a book's can be")
    }
}

/** A relative path made of real names only: no `..`, no `.`, no empty segment, no backslash. */
private fun isPlainPath(path: String): Boolean =
    '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
