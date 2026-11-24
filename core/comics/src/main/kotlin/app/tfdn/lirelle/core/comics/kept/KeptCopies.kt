package app.tfdn.lirelle.core.comics.kept

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.library.offline.KeptPages

/**
 * The pages the downloads keep of the albums, as the page reader takes them: before anything else, and with
 * their book in use for as long as they are read.
 */
internal class KeptCopies(private val files: DownloadedFiles, private val holds: BookHolds) {
    /**
     * Hands the kept pages of the album of [key] to [read], or null when the device does not keep it whole.
     * The book is in use from before the look, so that it is not deleted or swapped for a newer one in
     * between, until [read] ends, however it does; it is let go at once when there is nothing kept to read.
     */
    suspend fun reading(key: ComicKey, read: suspend (KeptPages?) -> Unit) {
        // The downloads file an album under its book: without one there is nothing to look for.
        val bookId = key.bookId ?: return read(null)
        holds.hold(bookId)
        var held = true
        try {
            val pages = files.pages(bookId, key.fileId)
            if (pages == null) {
                holds.release(bookId)
                held = false
            }
            read(pages)
        } finally {
            if (held) holds.release(bookId)
        }
    }
}
