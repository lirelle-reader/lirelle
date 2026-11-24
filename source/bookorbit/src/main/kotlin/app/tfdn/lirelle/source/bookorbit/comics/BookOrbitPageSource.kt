package app.tfdn.lirelle.source.bookorbit.comics

import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.await

private const val BlockBytes = 64 * 1024

/** The device could not take the bytes (no room left, a cache cleared under the fetch): not the server's doing. */
private class SinkFailure(cause: IOException) : RuntimeException(cause)

/**
 * The routes a BookOrbit server serves the pages of a book of this kind under; null for a PDF, which it serves
 * whole.
 */
private fun routesOf(kind: PagedKind): String? = when (kind) {
    PagedKind.Comic -> ComicRoutes
    PagedKind.Djvu -> DjvuRoutes
    PagedKind.Pdf -> null
}

/**
 * The pages of the comics and DjVus of a BookOrbit server, under its routes for each. Its calls block while
 * bytes are read and written: they are made where that is allowed.
 *
 * @param serverUrl the server of the profile's session, without a trailing slash, or null when there is none.
 * Read on every call.
 */
internal class BookOrbitPageSource(
    private val api: ApiProvider<ComicsApi>,
    private val errors: ErrorMapper,
    private val serverUrl: () -> String?,
) : PageSource {
    override fun servesByPages(kind: PagedKind): Boolean = routesOf(kind) != null

    override suspend fun pageCount(kind: PagedKind, fileId: FileId): ApiResult<Int> {
        val routes = routesOf(kind) ?: return ApiResult.Failure(ApiError.NotFound)
        val remote = remote() ?: return ApiResult.Failure(ApiError.SessionExpired)
        return errors.call(CallKind.Authenticated) { remote.pages(routes, fileId.bookOrbit()).pageCount }
    }

    override suspend fun page(kind: PagedKind, fileId: FileId, index: Int, sink: PageSink): ApiResult<Unit> {
        val routes = routesOf(kind) ?: return ApiResult.Failure(ApiError.NotFound)
        val remote = remote() ?: return ApiResult.Failure(ApiError.SessionExpired)
        // A failure of the sink is a RuntimeException of its own: the error mapper takes it for an unexpected one.
        return errors.call(CallKind.Authenticated) { remote.page(routes, fileId.bookOrbit(), index).writeTo(sink) }
    }

    override fun pageUrl(kind: PagedKind, fileId: FileId, index: Int): String? {
        val routes = routesOf(kind) ?: return null
        val server = serverUrl() ?: return null
        return "$server/api/v1/$routes/files/${fileId.bookOrbit()}/pages/$index"
    }

    private fun remote(): ComicsApi? = serverUrl()?.let(api::forServer)
}

/**
 * The answer's bytes, written into what [sink] opens. Reading them blocks, and a coroutine cancelled meanwhile
 * would only see it once bytes came: the call is cancelled with the fetch, which ends the read at once.
 */
private suspend fun Call<ResponseBody>.writeTo(sink: PageSink) = coroutineScope {
    val call = this@writeTo
    val over = AtomicBoolean()
    val watch = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!over.get()) call.cancel()
        }
    }
    try {
        call.await().use { it.writeTo(sink) }
    } finally {
        over.set(true)
        watch.cancel()
    }
}

/** A block at a time: a page is never held whole in memory, and a cancelled fetch stops between two blocks. */
private suspend fun ResponseBody.writeTo(sink: PageSink) {
    val source = byteStream()
    val block = ByteArray(BlockBytes)
    onDevice { sink.open() }.use { out ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = try {
                source.read(block)
            } catch (failure: IOException) {
                // A read ended by the fetch being given up is not a failure of the server.
                currentCoroutineContext().ensureActive()
                throw failure
            }
            if (read < 0) break
            onDevice { out.write(block, 0, read) }
        }
    }
}

/** A failure of [action] is the device's, and is told apart from the network's. */
private inline fun <T> onDevice(action: () -> T): T = try {
    action()
} catch (failure: IOException) {
    throw SinkFailure(failure)
}
