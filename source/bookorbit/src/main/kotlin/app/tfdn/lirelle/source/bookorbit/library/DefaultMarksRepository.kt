package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.PositionStatus
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.dto.BookmarkDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookmarkWriteDto
import app.tfdn.lirelle.source.bookorbit.library.dto.HighlightDto
import app.tfdn.lirelle.source.bookorbit.library.dto.HighlightWriteDto
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import app.tfdn.lirelle.source.bookorbit.network.markIdOf
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val TitleMaxLength = 500

/**
 * @param serverUrl the server of the profile it is made for, read on every call. A profile always has one,
 *   closed or not; the type allows none, which is answered as an expired session.
 */
internal class DefaultMarksRepository(
    private val api: ApiProvider<MarksApi>,
    private val errors: ErrorMapper,
    private val serverUrl: () -> String?,
) : MarksRepository {

    private suspend fun <T> call(block: suspend (MarksApi) -> T): ApiResult<T> {
        val remote = serverUrl()?.let(api::forServer) ?: return ApiResult.Failure(ApiError.SessionExpired)
        return errors.call(CallKind.Authenticated) { block(remote) }
    }

    override suspend fun bookmarks(bookId: BookId, fileId: FileId) = call { api ->
        api.bookmarks(bookId.bookOrbit(), fileId.bookOrbit()).map(::bookmark)
    }

    override suspend fun addBookmark(
        bookId: BookId,
        fileId: FileId,
        cfi: String,
        title: String,
    ): ApiResult<ServerBookmark> {
        val clean = cutTitle(title)
        if (clean.isEmpty()) return ApiResult.Failure(ApiError.Unexpected)
        val write = BookmarkWriteDto(cfi, clean, fileId.bookOrbit())
        return call { api -> bookmark(api.addBookmark(bookId.bookOrbit(), write)) }
    }

    override suspend fun deleteBookmark(bookId: BookId, id: MarkId) =
        call { api -> api.deleteBookmark(bookId.bookOrbit(), id.bookOrbit()) }

    override suspend fun highlights(bookId: BookId, fileId: FileId) = call { api ->
        api.highlights(bookId.bookOrbit(), fileId.bookOrbit()).map(::highlight)
    }

    override suspend fun addHighlight(bookId: BookId, highlight: NewHighlight) = call { api ->
        val write = HighlightWriteDto(
            cfi = highlight.cfi,
            bookFileId = highlight.fileId.bookOrbit(),
            text = highlight.text,
            color = highlight.color,
            note = highlight.note,
            chapterTitle = highlight.chapterTitle?.let(::cutTitle),
        )
        highlight(api.addHighlight(bookId.bookOrbit(), write))
    }

    override suspend fun changeHighlight(
        bookId: BookId,
        id: MarkId,
        color: String?,
        note: String?,
        clearNote: Boolean,
    ) = call { api ->
        val patch = buildJsonObject {
            color?.let { put("color", JsonPrimitive(it)) }
            if (clearNote) put("note", JsonNull) else note?.let { put("note", JsonPrimitive(it)) }
        }
        highlight(api.changeHighlight(bookId.bookOrbit(), id.bookOrbit(), patch))
    }

    override suspend fun deleteHighlight(bookId: BookId, id: MarkId) =
        call { api -> api.deleteHighlight(bookId.bookOrbit(), id.bookOrbit()) }

    override suspend fun restoreHighlight(id: MarkId) = call { api -> api.restoreHighlight(id.bookOrbit()) }
}

/** Trimmed, cut to 500 (the server's limit) without splitting a surrogate pair, trimmed again. */
private fun cutTitle(title: String): String {
    val cut = title.trim().take(TitleMaxLength)
    return (if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut).trim()
}

private fun origin(name: String?) = when (name) {
    "koreader" -> MarkOrigin.KoReader
    "kobo" -> MarkOrigin.Kobo
    else -> MarkOrigin.Web
}

private fun style(name: String?) = when (name) {
    "underline" -> HighlightStyle.Underline
    "strikethrough" -> HighlightStyle.Strikethrough
    "squiggly" -> HighlightStyle.Squiggly
    "invert" -> HighlightStyle.Invert
    else -> HighlightStyle.Highlight
}

private fun status(name: String?) = when (name) {
    "exact" -> PositionStatus.Exact
    "repaired" -> PositionStatus.Repaired
    "pending" -> PositionStatus.Pending
    "failed" -> PositionStatus.Failed
    else -> null
}

private fun bookmark(dto: BookmarkDto) = ServerBookmark(
    id = markIdOf(dto.id),
    cfi = dto.cfi,
    title = dto.title,
    note = dto.note,
    origin = origin(dto.origin),
    createdAt = instant(dto.createdAt) ?: Instant.EPOCH,
)

private fun highlight(dto: HighlightDto) = ServerHighlight(
    id = markIdOf(dto.id),
    cfi = dto.cfi,
    text = dto.text,
    color = dto.color,
    style = style(dto.style),
    note = dto.note,
    chapterTitle = dto.chapterTitle,
    chapterIndex = dto.chapterIndex,
    origin = origin(dto.origin),
    positionStatus = status(dto.positionStatus),
    createdAt = instant(dto.highlightedAt) ?: instant(dto.createdAt) ?: Instant.EPOCH,
    updatedAt = instant(dto.updatedAt),
)
