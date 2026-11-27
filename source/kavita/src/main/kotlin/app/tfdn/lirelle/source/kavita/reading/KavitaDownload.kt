package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.KavitaFileRef
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.ResponseBody
import retrofit2.HttpException

private const val CopyBlockBytes = 64 * 1024

private val ZipTypes = setOf("zip", "x-zip-compressed")

/** The device could not take the bytes (no room left, a cache cleared under the fetch): not the server's doing. */
private class SinkFailure(cause: IOException) : RuntimeException(cause)

/**
 * The files Kavita serves whole, into the reader cache's sink: an EPUB from `Download/chapter` (the first EPUB of a
 * zip when the chapter has several files, which needs the account's right to download), a PDF from `Reader/pdf`.
 * A comic is served by pages, not as a file.
 */
internal class KavitaDownload(private val server: KavitaReadingServer, private val errors: KavitaErrors) {

    suspend fun fetch(
        ref: KavitaFileRef,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiResult<Unit> = errors.call(KavitaCall.Authenticated) {
        when (server.chapter(ref).format) {
            "EPUB" -> {
                val answer = server.api().download(ref.chapterId)
                val body = answer.body()
                if (!answer.isSuccessful || body == null) throw HttpException(answer)
                withContext(Dispatchers.IO) { body.use { copyFile(it, isZip(it.contentType()), sink, onProgress) } }
            }
            "PDF" -> {
                val body = server.api().pdf(ref.chapterId)
                withContext(Dispatchers.IO) { body.use { copyFile(it, zip = false, sink, onProgress) } }
            }
            else -> throw ApiErrorException(ApiError.NotFound)
        }
    }
}

/** Kavita answers a chapter of several files as a zip of them. */
internal fun isZip(contentType: MediaType?): Boolean = contentType?.subtype?.lowercase() in ZipTypes

/**
 * The file in [body], into [sink], a block at a time: the body itself, or, for a [zip] of a chapter's files, its
 * first EPUB, the one Kavita's reader reads. [onProgress] counts the bytes of the body as they come. The sink is
 * neither closed nor cleaned up.
 */
internal suspend fun copyFile(
    body: ResponseBody,
    zip: Boolean,
    sink: OutputStream,
    onProgress: (DownloadProgress) -> Unit,
) {
    val total = body.contentLength().takeIf { it >= 0 }
    var read = 0L
    onProgress(DownloadProgress(read, total))
    val counted = object : FilterInputStream(body.byteStream()) {
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(block: ByteArray, offset: Int, length: Int): Int =
            super.read(block, offset, length).also { if (it > 0) count(it.toLong()) }

        private fun count(bytes: Long) {
            read += bytes
            onProgress(DownloadProgress(read, total))
        }
    }
    if (zip) copyFirstEpub(counted, sink) else copyBlocks(counted, sink)
    sink.onDevice { flush() }
}

/** The first entry of the zip in [source] that is an EPUB, into [sink]; unexpected when there is none. */
private suspend fun copyFirstEpub(source: InputStream, sink: OutputStream) {
    val zip = ZipInputStream(source)
    while (true) {
        currentCoroutineContext().ensureActive()
        val entry = zip.nextEntry ?: throw ApiErrorException(ApiError.Unexpected)
        if (!entry.isDirectory && entry.name.endsWith(".epub", ignoreCase = true)) {
            copyBlocks(zip, sink)
            return
        }
    }
}

private suspend fun copyBlocks(source: InputStream, sink: OutputStream) {
    val block = ByteArray(CopyBlockBytes)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = source.read(block)
        if (count < 0) break
        sink.onDevice { write(block, 0, count) }
    }
}

/** A failure of [action] on the sink is the device's, and is told apart from the network's. */
private inline fun OutputStream.onDevice(action: OutputStream.() -> Unit) = try {
    action()
} catch (failure: IOException) {
    throw SinkFailure(failure)
}
