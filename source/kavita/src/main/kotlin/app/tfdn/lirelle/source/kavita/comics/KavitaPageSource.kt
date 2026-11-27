package app.tfdn.lirelle.source.kavita.comics

import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.kavitaFile
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.network.copyBlocks
import app.tfdn.lirelle.source.kavita.network.onDevice
import app.tfdn.lirelle.source.kavita.reading.KavitaReadingApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.await

/**
 * The pages of the comics of a Kavita server (a chapter whose file is an archive or a folder of images). The app and
 * Kavita both count pages from 0, and the page count comes from the chapter's information. Kavita answers a page
 * past the end with the last one, so a negative index is refused here and the end is the count's. The key goes in
 * the address of a page: the profile's client adds it. Its calls block while bytes are read and written: they are
 * made where that is allowed.
 *
 * @param serverUrl the profile's server, without a trailing slash. Read on every call.
 */
internal class KavitaPageSource(
    private val api: KavitaApis<KavitaReadingApi>,
    private val errors: KavitaErrors,
    private val serverUrl: () -> String,
) : PageSource {

    override fun servesByPages(kind: PagedKind): Boolean = kind == PagedKind.Comic

    override suspend fun pageCount(kind: PagedKind, fileId: FileId): ApiResult<Int> {
        val chapterId = chapterOf(kind, fileId) ?: return ApiResult.Failure(ApiError.NotFound)
        return errors.call(KavitaCall.Authenticated) { api.forServer(serverUrl()).chapterInfo(chapterId).pages }
    }

    override suspend fun page(kind: PagedKind, fileId: FileId, index: Int, sink: PageSink): ApiResult<Unit> {
        val chapterId = chapterOf(kind, fileId)
        if (chapterId == null || index < 0) return ApiResult.Failure(ApiError.NotFound)
        // A failure of the sink is a RuntimeException of its own: the errors take it for an unexpected one.
        return errors.call(KavitaCall.Authenticated) {
            api.forServer(serverUrl()).page(chapterId, index).writeTo(sink)
        }
    }

    /** The page as Kavita has it, without the key: the profile's client adds it. */
    override fun pageUrl(kind: PagedKind, fileId: FileId, index: Int): String? {
        val chapterId = chapterOf(kind, fileId) ?: return null
        return serverUrl().toHttpUrl().newBuilder()
            .addPathSegments("api/Reader/image")
            .addQueryParameter("chapterId", chapterId.toString())
            .addQueryParameter("page", index.toString())
            .build()
            .toString()
    }

    /** The chapter a file of a kind read by pages belongs to; null for another kind, or an id that is not Kavita's. */
    private fun chapterOf(kind: PagedKind, fileId: FileId): Int? =
        if (servesByPages(kind)) fileId.kavitaFile()?.chapterId else null
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
    onDevice { sink.open() }.use { out -> copyBlocks(byteStream(), out) }
}
