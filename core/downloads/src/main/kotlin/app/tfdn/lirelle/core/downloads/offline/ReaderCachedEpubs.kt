package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.engine.CachedFiles
import app.tfdn.lirelle.core.downloads.engine.wholeFileOf
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

private const val PartSuffix = ".part"

/**
 * The readers' cache of whole files as a source of texts, an EPUB, a Kindle book, a FictionBook or a PDF: a
 * book read before it was downloaded, in the version planned, is copied from there. The cache keeps its own
 * file, which it may evict whenever it likes.
 */
class ReaderCachedEpubs @Inject constructor(private val cache: ReaderCache) : CachedFiles {
    override fun copyOf(file: PlannedFile, into: File): Boolean {
        val fileId = wholeFileOf(file) ?: return false
        val source = cache.cached(CachedBookKey(fileId, file.version)) ?: return false
        // Whole or not at all: written beside its place, then moved onto it.
        val part = File(into.path + PartSuffix)
        return try {
            Files.copy(source.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(part.toPath(), into.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: IOException) {
            // The cache dropped it meanwhile, or there is no room: the file is fetched.
            false
        } finally {
            part.delete()
        }
    }
}
