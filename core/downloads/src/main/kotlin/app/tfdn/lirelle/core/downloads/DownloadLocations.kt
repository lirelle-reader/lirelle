package app.tfdn.lirelle.core.downloads

import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId

/**
 * Where the profile's server serves a kept file now. The registry keeps what a file is ([FileSource]) and no
 * address: the address is asked here each time the file is fetched or looked for by it, so that it follows the
 * server's address and its routes, and a record holds nothing of one server.
 */
fun interface DownloadLocations {
    /**
     * The address of [source] of the book [bookId]; null when there is no server to ask (no session), or when the
     * server serves no address for a file of that kind (a page of a kind it serves no pages of).
     */
    fun url(bookId: BookId, source: FileSource): String?
}
