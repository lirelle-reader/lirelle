package app.tfdn.lirelle.core.ui

import coil3.Extras
import coil3.annotation.ExperimentalCoilApi
import coil3.getOrDefault
import coil3.network.CacheStrategy
import coil3.network.CacheStrategy.ReadResult
import coil3.network.CacheStrategy.WriteResult
import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import java.io.IOException

private const val CacheControl = "Cache-Control"
private const val Immutable = "immutable"
private const val NotModified = 304
private const val ServerErrors = 500
private val Success = 200..299

/** Set on a request sent while a usable copy of the image is on the disk. */
private val HasDiskCopy = Extras.Key(default = false)

/** What [StaleOnFailureClient] answers in the place of a server it could not reach. */
private object Unreachable

private val NetworkResponse.isImmutable: Boolean
    get() = headers[CacheControl].orEmpty().split(',').any { it.trim().equals(Immutable, ignoreCase = true) }

/**
 * When an image on the disk may be shown without asking the server.
 *
 * The server's cache headers decide, through [http]: a versioned cover is sent as immutable and
 * is never asked for again; an image without a version (a series tile, a portrait) is shown from
 * the disk for as long as the server allowed, then checked with its `ETag`, which costs an empty
 * "not modified" answer when it has not changed.
 *
 * A request sent while a copy is on the disk says so, so that [StaleOnFailureClient] can fall
 * back on the copy.
 */
@OptIn(ExperimentalCoilApi::class)
internal class CoverCacheStrategy(private val http: CacheStrategy) : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): ReadResult {
        val usable = cacheResponse.code in Success
        // Immutable for good: the standard rule would check it again after the max-age, a year.
        if (usable && cacheResponse.isImmutable) return ReadResult(cacheResponse)
        val result = http.read(cacheResponse, networkRequest, options)
        val request = result.request
        if (request == null || !usable) return result
        return ReadResult(request.copy(extras = request.extras.newBuilder().set(HasDiskCopy, true).build()))
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): WriteResult = if (networkResponse.delegate === Unreachable && cacheResponse != null) {
        // The copy is written back as it was, its age included: it is checked again next time.
        WriteResult(cacheResponse.copy(body = null))
    } else {
        http.write(cacheResponse, networkRequest, networkResponse, options)
    }
}

/**
 * Keeps covers already seen on screen without the server: when a copy of the image is on the
 * disk and the server cannot be reached, or fails, the answer is "not modified" and the copy is
 * shown, however old.
 */
internal class StaleOnFailureClient(private val client: NetworkClient) : NetworkClient {

    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T {
        if (!request.extras.getOrDefault(HasDiskCopy)) return client.executeRequest(request, block)
        val unreachable = NetworkResponse(code = NotModified, delegate = Unreachable)
        var answered = false
        return try {
            client.executeRequest(request) { response ->
                answered = true
                // 504 is also what the HTTP client answers by itself when told not to use the network.
                block(if (response.code >= ServerErrors) unreachable else response)
            }
        } catch (exception: IOException) {
            // Once the server has answered, a failure is one of reading or storing its answer.
            if (answered) throw exception
            block(unreachable)
        }
    }
}
