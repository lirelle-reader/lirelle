package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import app.tfdn.lirelle.core.network.ApiResult

/** The bookmarks and highlights of a book on the server. Places are given in the file [fileId]. */
interface MarksRepository {
    suspend fun bookmarks(bookId: BookId, fileId: FileId): ApiResult<List<ServerBookmark>>

    /** The same place gives the same bookmark. [title] is trimmed and cut to 500; empty is refused. */
    suspend fun addBookmark(bookId: BookId, fileId: FileId, cfi: String, title: String): ApiResult<ServerBookmark>

    suspend fun deleteBookmark(bookId: BookId, id: MarkId): ApiResult<Unit>

    suspend fun highlights(bookId: BookId, fileId: FileId): ApiResult<List<ServerHighlight>>

    suspend fun addHighlight(bookId: BookId, highlight: NewHighlight): ApiResult<ServerHighlight>

    /** Only what is not null is sent; [clearNote] sends `note: null`. */
    suspend fun changeHighlight(
        bookId: BookId,
        id: MarkId,
        color: String? = null,
        note: String? = null,
        clearNote: Boolean = false,
    ): ApiResult<ServerHighlight>

    /** Moves it to the trash. */
    suspend fun deleteHighlight(bookId: BookId, id: MarkId): ApiResult<Unit>

    suspend fun restoreHighlight(id: MarkId): ApiResult<Unit>
}
