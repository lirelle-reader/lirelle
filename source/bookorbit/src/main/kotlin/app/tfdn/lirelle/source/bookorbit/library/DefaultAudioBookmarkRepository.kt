package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.dto.AudioBookmarkDto
import app.tfdn.lirelle.source.bookorbit.library.dto.AudioBookmarkWriteDto
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import retrofit2.HttpException

private const val Conflict = 409
private const val TitleMaxLength = 500
private const val NoteMaxLength = 4000

/**
 * @param serverUrl the server of the profile it is made for, read on every call. A profile always has one,
 *   closed or not; the type allows none, which is answered as an expired session.
 */
internal class DefaultAudioBookmarkRepository(
    private val api: ApiProvider<AudioBookmarkApi>,
    private val errors: ErrorMapper,
    private val serverUrl: () -> String?,
) : AudioBookmarkRepository {

    private suspend fun <T> call(block: suspend (AudioBookmarkApi) -> T): ApiResult<T> {
        val remote = serverUrl()?.let(api::forServer) ?: return ApiResult.Failure(ApiError.SessionExpired)
        return errors.call(CallKind.Authenticated) { block(remote) }
    }

    override suspend fun list(bookId: BookId): ApiResult<List<AudioBookmark>> = call { api ->
        api.list(bookId.bookOrbit()).map { it.toModel() }.sortedWith(AudioBookmark.PositionOrder)
    }

    override suspend fun create(bookId: BookId, bookmark: AudioBookmark): ApiResult<BookmarkCreateResult> {
        val title = cut(bookmark.title, TitleMaxLength)
        if (title.isEmpty()) return ApiResult.Failure(ApiError.Unexpected)
        val body = AudioBookmarkWriteDto(
            clientId = bookmark.id,
            positionMs = bookmark.positionMs,
            chapterId = bookmark.chapterId,
            title = title,
            note = bookmark.note?.let { cut(it, NoteMaxLength) }?.takeUnless { it.isEmpty() },
        )
        val result = call<BookmarkCreateResult?> { api ->
            val response = api.create(bookId.bookOrbit(), body)
            if (response.code() == Conflict) return@call BookmarkCreateResult.SameSecond
            if (!response.isSuccessful) throw HttpException(response)
            response.body()?.let { BookmarkCreateResult.Created(it.toModel()) }
        }
        // A 2xx without a bookmark cannot be told from a lost answer.
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> result.value?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(ApiError.Unexpected)
        }
    }

    override suspend fun edit(bookId: BookId, id: String, title: String, note: String?): ApiResult<AudioBookmark> =
        call { api ->
            val kept = note?.let { cut(it, NoteMaxLength) }?.takeUnless { it.isEmpty() }
            val patch = buildJsonObject {
                put("title", JsonPrimitive(cut(title, TitleMaxLength)))
                put("note", kept?.let(::JsonPrimitive) ?: JsonNull)
            }
            api.edit(bookId.bookOrbit(), id, patch).toModel()
        }

    override suspend fun delete(bookId: BookId, id: String): ApiResult<Unit> =
        call { api -> api.delete(bookId.bookOrbit(), id) }
}

/** Trimmed, cut to [max] without splitting a surrogate pair, trimmed again. */
private fun cut(text: String, max: Int): String {
    val cut = text.trim().take(max)
    return (if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut).trim()
}

private fun AudioBookmarkDto.toModel() = AudioBookmark(
    id = id,
    bookId = bookIdOf(bookId),
    positionMs = positionMs,
    chapterId = chapterId,
    title = title,
    note = note,
    createdAt = instant(createdAt),
    updatedAt = instant(updatedAt),
)
