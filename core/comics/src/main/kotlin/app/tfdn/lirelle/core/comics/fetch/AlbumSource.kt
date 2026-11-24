package app.tfdn.lirelle.core.comics.fetch

import app.tfdn.lirelle.core.comics.PageSource
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.store.Album
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val PartSuffix = ".part"

/**
 * The server's side of the albums of one [kind]: their count, and their pages put in their places. Its calls
 * block while bytes are read and written: they are made where that is allowed.
 *
 * @param source the profile's source, which fetches the pages.
 */
internal class AlbumSource(private val source: PageSource, private val kind: PagedKind) {
    suspend fun pageCount(fileId: FileId): ApiResult<Int> = source.pageCount(kind, fileId)

    /**
     * Fetches the page [index] into its place in the [album]: written beside it under a temporary name and
     * moved there in one step once it is whole and is a picture, so that a file in place is always a page. The
     * temporary name is this fetch's alone: the page may be asked for again while a fetch given up is still
     * ending. Nothing is left behind otherwise, cancellation included, and a fetch given up puts nothing in
     * place; nor does one that ends after the album's profile closed.
     */
    suspend fun page(album: Album, index: Int): PageState {
        val place = album.page(index)
        val part = File(place.parentFile, "${place.name}.${UUID.randomUUID()}$PartSuffix")
        return try {
            val fetched = source.page(kind, album.fileId, index) {
                if (!album.makeFolder()) throw IOException("The album's folder is not there")
                part.outputStream()
            }
            currentCoroutineContext().ensureActive()
            if (fetched is ApiResult.Failure) PageState.Failed(fetched.error) else putInPlace(part, place)
        } catch (_: IOException) {
            // The device's side: the album's folder went while the page was put in place.
            PageState.Failed(ApiError.Unexpected)
        } finally {
            part.delete()
        }
    }

    /** What came is kept only if it is a picture. */
    private fun putInPlace(part: File, place: File): PageState {
        val size = imageSize(part) ?: return PageState.Failed(ApiError.Unexpected)
        Files.move(part.toPath(), place.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return PageState.Ready(place, size.width, size.height)
    }
}
