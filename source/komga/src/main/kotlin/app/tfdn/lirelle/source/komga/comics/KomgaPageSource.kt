package app.tfdn.lirelle.source.komga.comics

import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaCall
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.await

private const val BlockBytes = 64 * 1024

/**
 * The formats of the pictures the device draws as they are on Android 14, the app's least (AVIF since 12, HEIF since
 * 9). Any other — a JPEG XL, a TIFF, one unknown — is asked of Komga as a JPEG.
 */
private val DrawnFormats = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
    "image/bmp",
    "image/avif",
    "image/heif",
    "image/heic",
)

/** Whether a page of this [mediaType] is asked of Komga as a JPEG: the device cannot draw it as it is. */
internal fun needsJpeg(mediaType: String): Boolean =
    mediaType.substringBefore(';').trim().lowercase() !in DrawnFormats

/**
 * The address of the page Komga numbers [number] (from 1) of the book whose file is [fileId], on [serverUrl] (base
 * path kept): the page as Komga has it, the address its own manifests give.
 */
internal fun pageAddress(serverUrl: String, fileId: FileId, number: Int): HttpUrl =
    serverUrl.toHttpUrl().newBuilder()
        .addPathSegments("api/v1/books")
        .addPathSegment(fileId.value)
        .addPathSegment("pages")
        .addPathSegment(number.toString())
        .build()

/** The formats of a Komga comic's pages, by the app's index (from 0): their number is the book's page count. */
internal fun interface KomgaPageFormats {
    /** Throws what the listing failed with. */
    suspend fun formats(fileId: FileId): List<String>
}

/** The device could not take the bytes (no room left, a cache cleared under the fetch): not the server's doing. */
private class SinkFailure(cause: IOException) : RuntimeException(cause)

/**
 * The pages of the comics of a Komga server (a book whose media is `DIVINA`): a book is one file, whose id is the
 * book's. The app counts pages from 0, Komga from 1. The formats of a book's pages are listed once, with the count,
 * and kept while the profile is open: a page the device cannot draw as it is is asked as a JPEG. A book whose media
 * Komga has not made ready, and a page refused to an account without the page streaming role, are not found. Its
 * calls block while bytes are read and written: they are made where that is allowed.
 *
 * @param serverUrl the profile's server, without a trailing slash. Read on every call.
 */
internal class KomgaPageSource(
    private val api: KomgaApis<KomgaPagesApi>,
    private val errors: KomgaErrors,
    private val serverUrl: () -> String,
) : PageSource, KomgaPageFormats {

    /** The formats of each book's pages, by the app's index, as last listed. */
    private val formats = ConcurrentHashMap<FileId, List<String>>()

    /** One listing of a book's pages at a time: the count and the first pages of an opening share it. */
    private val listing = ConcurrentHashMap<FileId, Mutex>()

    override fun servesByPages(kind: PagedKind): Boolean = kind == PagedKind.Comic

    /** Listed again at each opening, the book having maybe changed since: once, whoever else asks meanwhile. */
    override suspend fun pageCount(kind: PagedKind, fileId: FileId): ApiResult<Int> {
        if (!servesByPages(kind)) return ApiResult.Failure(ApiError.NotFound)
        return errors.call(KomgaCall.Content) { formatsOf(fileId, fresh = true).size }
    }

    override suspend fun page(kind: PagedKind, fileId: FileId, index: Int, sink: PageSink): ApiResult<Unit> {
        if (!servesByPages(kind)) return ApiResult.Failure(ApiError.NotFound)
        // A failure of the sink is a RuntimeException of its own: the errors take it for an unexpected one.
        return errors.call(KomgaCall.Content) {
            val pages = formatsOf(fileId, fresh = false)
            // A page past the end is not asked: Komga may refuse it as a bad request rather than as not found.
            val format = pages.getOrNull(index) ?: throw ApiErrorException(ApiError.NotFound)
            val convert = JpegConversion.takeIf { needsJpeg(format) }
            api.forServer(serverUrl()).page(fileId.value, index + 1, convert).writeTo(sink)
        }
    }

    /** Before the book's pages are listed, the page as Komga has it. */
    override fun pageUrl(kind: PagedKind, fileId: FileId, index: Int): String? {
        if (!servesByPages(kind)) return null
        val format = formats[fileId]?.getOrNull(index)
        return pageAddress(serverUrl(), fileId, index + 1).newBuilder()
            .apply { if (format != null && needsJpeg(format)) addQueryParameter("convert", JpegConversion) }
            .build()
            .toString()
    }

    /** Those kept, or listed once. */
    override suspend fun formats(fileId: FileId): List<String> = formatsOf(fileId, fresh = false)

    /**
     * The formats of the book's pages: those kept, unless [fresh]; otherwise listed, one listing at a time. A
     * listing that ended while this call waited for its turn serves it, fresh or not: it is as new as can be.
     */
    private suspend fun formatsOf(fileId: FileId, fresh: Boolean): List<String> {
        val seen = formats[fileId]
        if (!fresh && seen != null) return seen
        return listing.computeIfAbsent(fileId) { Mutex() }.withLock {
            val now = formats[fileId]
            if (now != null && (!fresh || now !== seen)) now else list(fileId)
        }
    }

    /** The formats of the book's pages, asked of Komga, and kept. */
    private suspend fun list(fileId: FileId): List<String> =
        api.forServer(serverUrl()).pages(fileId.value).sortedBy { it.number }.map { it.mediaType }
            .also { formats[fileId] = it }
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
