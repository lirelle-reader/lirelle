package app.tfdn.lirelle.core.library.offline

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import java.io.File

/**
 * The pages the device keeps of a file the server serves page by page (a comic, a DjVu): all of them, as
 * pictures, each in a file of its own.
 *
 * @property count how many there are.
 * @param place where the page of an index is.
 */
class KeptPages(val count: Int, private val place: (Int) -> File) {
    /** The picture of the page [index], from 0, as the server served it. */
    fun file(index: Int): File = place(index)
}

/** The files the device keeps of the books downloaded, for the reader and the player to open before anything else. */
interface DownloadedFiles {
    /**
     * The kept copy of [fileId], the file of the book's text (an EPUB, a Kindle book, a FictionBook, a PDF),
     * whatever its version, when a format that uses it is complete.
     */
    suspend fun text(bookId: BookId, fileId: FileId): File?

    /**
     * The kept pages of [fileId], the file of a book the server serves page by page, whatever their version,
     * when the book's text is complete; null otherwise, and for an album only part of which is fetched. Who
     * keeps no pages has nothing to say.
     */
    suspend fun pages(bookId: BookId, fileId: FileId): KeptPages? = null

    /**
     * The kept audio file of an audiobook asset or a narration track, when its format is complete; the order of
     * the query parameters of [url] does not matter. It never waits: the player asks from its loading thread.
     */
    fun audio(url: String): File?

    /** Nothing is kept. */
    object None : DownloadedFiles {
        override suspend fun text(bookId: BookId, fileId: FileId): File? = null

        override fun audio(url: String): File? = null
    }
}
