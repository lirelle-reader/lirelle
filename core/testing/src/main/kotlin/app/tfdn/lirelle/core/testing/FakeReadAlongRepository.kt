package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Scriptable [ReadAlongRepository]. Set the result fields, then read what was asked.
 * [gate], when set, holds every suspending call until it is completed.
 */
class FakeReadAlongRepository : ReadAlongRepository {
    var playlistResult: ApiResult<ReadAlongPlaylist> = ApiResult.Failure(ApiError.ServerUnreachable)
    var spineResult: ApiResult<List<SpineEntry>> = ApiResult.Failure(ApiError.ServerUnreachable)
    var serverUrl: String? = "https://books.example"
    var gate: CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()

    private suspend fun <T> answer(call: String, result: T): T {
        calls += call
        gate?.await()
        return result
    }

    override suspend fun playlist(bookId: BookId, fileId: FileId) = answer("playlist $bookId $fileId", playlistResult)

    override suspend fun spine(bookId: BookId, fileId: FileId) = answer("spine $bookId $fileId", spineResult)

    /** Under no server's routes: `<server>/narration/<bookId>/<fileId>/<audioHref>`. */
    override fun trackUrl(bookId: BookId, fileId: FileId, audioHref: String): String? =
        serverUrl?.let { "$it/narration/$bookId/$fileId/$audioHref" }
}
