package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.network.ApiResult

/** The bookmarks of an audiobook, as the server keeps them. */
interface AudioBookmarkRepository {
    /** In position order, then creation order. */
    suspend fun list(bookId: BookId): ApiResult<List<AudioBookmark>>

    /**
     * Sends [bookmark] with its id as the client id: sending it again answers the one stored. Its
     * `createdAt` and `updatedAt` are not sent.
     */
    suspend fun create(bookId: BookId, bookmark: AudioBookmark): ApiResult<BookmarkCreateResult>

    /** A null or blank [note] clears it. */
    suspend fun edit(bookId: BookId, id: String, title: String, note: String?): ApiResult<AudioBookmark>

    suspend fun delete(bookId: BookId, id: String): ApiResult<Unit>
}
