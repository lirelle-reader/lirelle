package app.tfdn.lirelle.source.kavita.comics

import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult

/** A Kavita profile's pages until its books are read (#51): none is served. The server is asked nothing. */
internal class NoKavitaPages : PageSource {
    override fun servesByPages(kind: PagedKind): Boolean = false

    override suspend fun pageCount(kind: PagedKind, fileId: FileId): ApiResult<Int> =
        ApiResult.Failure(ApiError.NotFound)

    override suspend fun page(kind: PagedKind, fileId: FileId, index: Int, sink: PageSink): ApiResult<Unit> =
        ApiResult.Failure(ApiError.NotFound)

    override fun pageUrl(kind: PagedKind, fileId: FileId, index: Int): String? = null
}
