package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.model.OverlayItem
import app.tfdn.lirelle.core.library.model.OverlayResource
import app.tfdn.lirelle.core.library.model.OverlaySection
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayMapDto
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import app.tfdn.lirelle.source.bookorbit.network.fileIdOf
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MillisPerSecond = 1000.0
private const val HexRadix = 16
private const val ByteMask = 0xFF
private const val HexDigits = 2
private const val Unreserved = "-._~"

/**
 * @param serverUrl the server of the profile it is made for, read on every call. A profile always has one,
 *   closed or not; the type allows none, which is answered as an expired session.
 * @param compute where a map of thousands of sentences is turned into the model: not the caller's thread, which
 *   may be the main one.
 */
internal class DefaultReadAlongRepository(
    private val api: ApiProvider<ReadAlongApi>,
    private val errors: ErrorMapper,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val serverUrl: () -> String?,
) : ReadAlongRepository {

    private suspend fun <T> call(block: suspend (ReadAlongApi) -> T): ApiResult<T> {
        val remote = serverUrl()?.let(api::forServer) ?: return ApiResult.Failure(ApiError.SessionExpired)
        return errors.call(CallKind.Authenticated) { block(remote) }
    }

    override suspend fun playlist(bookId: BookId, fileId: FileId): ApiResult<ReadAlongPlaylist> = call { api ->
        val answer = api.mediaOverlay(bookId.bookOrbit(), fileId.bookOrbit())
        withContext(compute) { answer.toModel() }
    }

    override suspend fun spine(bookId: BookId, fileId: FileId): ApiResult<List<SpineEntry>> = call { api ->
        val info = api.info(bookId.bookOrbit(), fileId.bookOrbit())
        withContext(compute) {
            val sizes = info.manifest.associate { it.id to it.size }
            info.spine.mapIndexed { index, entry -> SpineEntry(index, entry.href, sizes[entry.idref]) }
        }
    }

    override fun trackUrl(bookId: BookId, fileId: FileId, audioHref: String): String? = serverUrl()?.let { server ->
        val path = audioHref.split('/').joinToString("/", transform = ::encodeSegment)
        "$server/api/v1/epub/${bookId.bookOrbit()}/media-overlay/file/$path?fileId=${fileId.bookOrbit()}"
    }
}

private fun MediaOverlayMapDto.toModel() = ReadAlongPlaylist(
    bookId = bookIdOf(bookId),
    fileId = fileIdOf(fileId),
    durationMs = durationSeconds?.let(::millis),
    items = items.map {
        OverlayItem(
            sectionIndex = it.sectionIndex,
            textHref = it.textHref,
            fragment = it.textFragment,
            audioHref = it.audioHref,
            clipBeginMs = millis(it.clipBeginSeconds),
            clipEndMs = it.clipEndSeconds?.let(::millis),
        )
    },
    sections = sections.map { OverlaySection(it.index, it.href, it.label) },
    resources = resources.map { OverlayResource(it.href, it.mediaType, it.size) },
)

private fun millis(seconds: Double): Long = (seconds * MillisPerSecond).roundToLong()

private fun Char.isUnreserved() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in Unreserved

/** RFC 3986 path segment: all but the unreserved characters are written as percent-encoded UTF-8 bytes. */
private fun encodeSegment(segment: String): String = buildString {
    for (byte in segment.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and ByteMask
        val char = code.toChar()
        if (char.isUnreserved()) {
            append(char)
        } else {
            append('%').append(code.toString(HexRadix).uppercase().padStart(HexDigits, '0'))
        }
    }
}
