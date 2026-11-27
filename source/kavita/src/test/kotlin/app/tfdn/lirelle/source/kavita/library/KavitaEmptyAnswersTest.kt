package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.downloads.KavitaDownloadRefusals
import app.tfdn.lirelle.source.kavita.network.KavitaAccountPictures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

private val Book = BookId("12")
private val File = FileId("34")
private val NotFound = ApiResult.Failure(ApiError.NotFound)
private val Nothing = ApiResult.Success(emptyList<Any>())

/**
 * What Kavita does not have: no audio, no mark, no narration (every write is refused, nothing is asked of the
 * server), and how a refused download is told.
 */
class KavitaEmptyAnswersTest {

    @Test
    fun `no audio, no marks and no narration are served`() = runBlocking {
        assertThat(EmptyKavitaAudio().assetUrl(Book, "a")).isNull()
        assertThat(EmptyKavitaAudio().manifest(Book)).isEqualTo(NotFound)
        assertThat(EmptyKavitaAudioBookmarks().list(Book)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().bookmarks(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().highlights(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().addBookmark(Book, File, "cfi", "title")).isEqualTo(NotFound)
        assertThat(EmptyKavitaReadAlong().trackUrl(Book, File, "a.mp3")).isNull()
        assertThat(KavitaAccountPictures.pictureUrl("https://kavita.example.com", "/avatar")).isNull()
    }

    @Test
    fun `a refusal of a file is read from its status`() {
        val refusals = KavitaDownloadRefusals()

        assertThat(refusals.refusal(response(404))).isEqualTo(ApiError.NotFound)
        assertThat(refusals.refusal(response(503))).isEqualTo(ApiError.ServerFailed)
        assertThat(refusals.refusal(response(429))).isEqualTo(ApiError.TooManyAttempts)
        assertThat(refusals.refusal(response(401))).isEqualTo(ApiError.SessionExpired)
        assertThat(refusals.refusal(response(403))).isEqualTo(ApiError.Unexpected)
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://kavita.example.com/api/Download/chapter").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body("".toResponseBody())
        .build()
}
