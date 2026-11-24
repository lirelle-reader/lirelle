package app.tfdn.lirelle.core.testing

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
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred

private const val TitleMaxLength = 500

/**
 * In-memory [MarksRepository]: the lists behave like the server's (the same place gives the same
 * bookmark; a deleted highlight goes to [trash] and can be restored). [failure], when set, makes
 * every call fail with it, after recording it in [calls]. A row that is not there is
 * [ApiError.NotFound], as on the server. [gate], when set, holds every call.
 */
class FakeMarksRepository : MarksRepository {
    val bookmarkList = mutableListOf<ServerBookmark>()
    val highlightList = mutableListOf<ServerHighlight>()
    val trash = mutableListOf<ServerHighlight>()
    var failure: ApiError? = null
    var gate: CompletableDeferred<Unit>? = null

    /** The clock of the created items. */
    var now: Instant = Instant.parse("2026-11-14T12:00:00Z")

    val calls = mutableListOf<String>()

    /** The next item's id is this count's text: `1`, `2`… */
    private var nextId = 1

    private fun newId() = MarkId("${nextId++}")

    /** Records the call, waits at the gate, then runs [result]; a null from it is a missing row. */
    private suspend fun <T : Any> answer(call: String, result: () -> T?): ApiResult<T> {
        calls += call
        gate?.await()
        failure?.let { return ApiResult.Failure(it) }
        return result()?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NotFound)
    }

    override suspend fun bookmarks(bookId: BookId, fileId: FileId) =
        answer("bookmarks $bookId $fileId") { bookmarkList.toList() }

    override suspend fun addBookmark(
        bookId: BookId,
        fileId: FileId,
        cfi: String,
        title: String,
    ): ApiResult<ServerBookmark> {
        val cut = title.trim().take(TitleMaxLength)
        val clean = (if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut).trim()
        if (clean.isEmpty()) return ApiResult.Failure(ApiError.Unexpected)
        return answer("addBookmark $bookId $fileId $cfi $title") {
            bookmarkList.firstOrNull { it.cfi == cfi }
                ?: ServerBookmark(newId(), cfi, clean, null, MarkOrigin.Web, now).also { bookmarkList += it }
        }
    }

    override suspend fun deleteBookmark(bookId: BookId, id: MarkId) = answer("deleteBookmark $bookId $id") {
        bookmarkList.firstOrNull { it.id == id }?.let {
            bookmarkList.remove(it)
            Unit
        }
    }

    override suspend fun highlights(bookId: BookId, fileId: FileId) =
        answer("highlights $bookId $fileId") { highlightList.toList() }

    override suspend fun addHighlight(bookId: BookId, highlight: NewHighlight) =
        answer("addHighlight $bookId ${highlight.cfi}") {
            ServerHighlight(
                id = newId(),
                cfi = highlight.cfi,
                text = highlight.text,
                color = highlight.color,
                style = HighlightStyle.Highlight,
                note = highlight.note,
                chapterTitle = highlight.chapterTitle,
                chapterIndex = null,
                origin = MarkOrigin.Web,
                positionStatus = PositionStatus.Exact,
                createdAt = now,
                updatedAt = null,
            ).also { highlightList += it }
        }

    override suspend fun changeHighlight(
        bookId: BookId,
        id: MarkId,
        color: String?,
        note: String?,
        clearNote: Boolean,
    ) = answer("changeHighlight $bookId $id color=$color note=$note clearNote=$clearNote") {
        val index = highlightList.indexOfFirst { it.id == id }
        highlightList.getOrNull(index)?.let { old ->
            old.copy(
                color = color ?: old.color,
                note = if (clearNote) null else note ?: old.note,
                updatedAt = now,
            ).also { highlightList[index] = it }
        }
    }

    override suspend fun deleteHighlight(bookId: BookId, id: MarkId) = answer("deleteHighlight $bookId $id") {
        highlightList.firstOrNull { it.id == id }?.let {
            highlightList.remove(it)
            trash += it
            Unit
        }
    }

    override suspend fun restoreHighlight(id: MarkId) = answer("restoreHighlight $id") {
        trash.firstOrNull { it.id == id }?.let {
            trash.remove(it)
            highlightList += it
            Unit
        }
    }
}
