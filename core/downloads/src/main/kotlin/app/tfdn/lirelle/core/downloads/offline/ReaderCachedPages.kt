package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.comics.ServedPages
import app.tfdn.lirelle.core.downloads.engine.CachedFiles
import app.tfdn.lirelle.core.downloads.engine.isKindOf
import app.tfdn.lirelle.core.downloads.engine.pageOf
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

private const val PartSuffix = ".part"

/**
 * The page reader's cache of pages seen as a source of a kept album's pages: a page read before the book was
 * downloaded, of the version planned, is copied from there. The cache keeps its own, which it may evict
 * whenever it likes.
 */
internal class ReaderCachedPages @Inject constructor(private val served: ServedPages) : CachedFiles {
    override fun copyOf(file: PlannedFile, into: File): Boolean {
        val page = pageOf(file) ?: return false
        val source = served.cached(page.fileId, file.version, page.index) ?: return false
        // Whole or not at all: written beside its place, then moved onto it.
        val part = File(into.path + PartSuffix)
        return try {
            Files.copy(source.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING)
            // Checked as a page received is: the download keeps pictures only.
            val picture = file.kind.isKindOf(part)
            if (picture) Files.move(part.toPath(), into.toPath(), StandardCopyOption.ATOMIC_MOVE)
            picture
        } catch (_: IOException) {
            // The reader's cache dropped it meanwhile, or there is no room: the page is fetched.
            false
        } finally {
            part.delete()
        }
    }
}
