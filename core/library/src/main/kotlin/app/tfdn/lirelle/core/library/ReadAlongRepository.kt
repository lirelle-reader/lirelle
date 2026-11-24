package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.network.ApiResult

/** What a read-along book adds to an EPUB: the map of sentences to audio, and the audio itself. */
interface ReadAlongRepository {
    /** The parsed sync map; a large book's answer is several MB: the download read timeout applies. */
    suspend fun playlist(bookId: BookId, fileId: FileId): ApiResult<ReadAlongPlaylist>

    suspend fun spine(bookId: BookId, fileId: FileId): ApiResult<List<SpineEntry>>

    /**
     * `epub/{bookId}/media-overlay/file/<audioHref>?fileId=`, each path segment encoded once. Null only when
     * the repository has no server to name it on, which that of a profile always has.
     */
    fun trackUrl(bookId: BookId, fileId: FileId, audioHref: String): String?
}
