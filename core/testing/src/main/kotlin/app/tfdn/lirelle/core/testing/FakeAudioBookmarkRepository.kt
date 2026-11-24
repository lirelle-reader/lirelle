package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred

/**
 * Scriptable [AudioBookmarkRepository]. Set the result fields, then read what was asked.
 *
 * With [online], it keeps a server of its own in [stored]: a create stores the bookmark (or answers
 * [BookmarkCreateResult.SameSecond] when another one is at that second, or when its id was deleted: as on
 * the server, a deleted id stays taken), an edit and a delete change it,
 * and the list reads it. Offline, every call answers [offlineError]. A result field set to non-null
 * answers in place of the server, every time. [gate], when set, holds every call until it is completed.
 */
class FakeAudioBookmarkRepository : AudioBookmarkRepository {
    var online = true
    var offlineError: ApiError = ApiError.ServerUnreachable
    var listResult: ApiResult<List<AudioBookmark>>? = null
    var createResult: ApiResult<BookmarkCreateResult>? = null
    var editResult: ApiResult<AudioBookmark>? = null
    var deleteResult: ApiResult<Unit>? = null
    var gate: CompletableDeferred<Unit>? = null

    /** The server's bookmarks, live ones only. */
    val stored = mutableListOf<AudioBookmark>()

    /** Every call, as "list <book>", "create <book> <id>", "edit <book> <id>", "delete <book> <id>". */
    val calls = mutableListOf<String>()
    val created = mutableListOf<AudioBookmark>()

    /** The ids of the bookmarks deleted: they stay taken. */
    val deleted = mutableSetOf<String>()

    private suspend fun <T> answer(call: String, scripted: ApiResult<T>?, server: () -> ApiResult<T>): ApiResult<T> {
        calls += call
        gate?.await()
        return scripted ?: if (online) server() else ApiResult.Failure(offlineError)
    }

    override suspend fun list(bookId: BookId) = answer("list $bookId", listResult) {
        ApiResult.Success(stored.filter { it.bookId == bookId }.sortedBy { it.positionMs })
    }

    override suspend fun create(bookId: BookId, bookmark: AudioBookmark): ApiResult<BookmarkCreateResult> {
        created += bookmark
        return answer("create $bookId ${bookmark.id}", createResult) {
            val same = stored.firstOrNull { it.id == bookmark.id }
            val second = AudioBookmark.secondOf(bookmark.positionMs)
            val clash = stored.any { it.bookId == bookId && AudioBookmark.secondOf(it.positionMs) == second }
            when {
                same != null -> ApiResult.Success(BookmarkCreateResult.Created(same))
                clash || bookmark.id in deleted -> ApiResult.Success(BookmarkCreateResult.SameSecond)
                else -> {
                    val kept = bookmark.copy(createdAt = StoredAt, updatedAt = StoredAt)
                    stored += kept
                    ApiResult.Success(BookmarkCreateResult.Created(kept))
                }
            }
        }
    }

    override suspend fun edit(bookId: BookId, id: String, title: String, note: String?) =
        answer("edit $bookId $id", editResult) {
            val at = stored.indexOfFirst { it.id == id }
            if (at < 0) return@answer ApiResult.Failure(ApiError.NotFound)
            val edited = stored[at].copy(title = title, note = note?.takeUnless { it.isBlank() })
            stored[at] = edited
            ApiResult.Success(edited)
        }

    override suspend fun delete(bookId: BookId, id: String) = answer("delete $bookId $id", deleteResult) {
        if (stored.removeAll { it.id == id }) {
            deleted += id
            ApiResult.Success(Unit)
        } else {
            ApiResult.Failure(ApiError.NotFound)
        }
    }

    private companion object {
        val StoredAt: Instant = Instant.parse("2026-11-15T12:00:00Z")
    }
}
