package app.tfdn.lirelle.source.bookorbit.comics

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

/** The routes of the comics, the same for CBZ, CBR and CB7 files. */
internal const val ComicRoutes = "cbz"

/** The routes of a DjVu, which answer as the comics' do: its pages are pictures the server draws. */
internal const val DjvuRoutes = "djvu"

/**
 * The pages of a book served by pages, relative to `<server>/api/v1/`. The server has the same two routes
 * under [ComicRoutes] and under [DjvuRoutes]: `routes` is one of them.
 */
internal interface ComicsApi {
    @GET("{routes}/files/{fileId}/pages")
    suspend fun pages(@Path("routes") routes: String, @Path("fileId") fileId: Long): PageCountDto

    /**
     * The picture as the server has it; [index] counts from 0, and one past the end answers 404. The call
     * itself is given, not its answer: its bytes come while they are read, and only the call can be cancelled
     * then.
     */
    @Streaming
    @GET("{routes}/files/{fileId}/pages/{index}")
    fun page(
        @Path("routes") routes: String,
        @Path("fileId") fileId: Long,
        @Path("index") index: Int,
    ): Call<ResponseBody>
}

@Serializable
internal data class PageCountDto(val pageCount: Int)
