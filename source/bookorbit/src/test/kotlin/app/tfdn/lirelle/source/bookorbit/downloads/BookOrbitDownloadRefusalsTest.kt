package app.tfdn.lirelle.source.bookorbit.downloads

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

private val Json = "application/json".toMediaType()

/** A BookOrbit server's refusals of a kept file, read as those of its other calls with the session's token. */
class BookOrbitDownloadRefusalsTest {

    private val refusals = BookOrbitDownloadRefusals(ErrorMapper(ApiJson))

    // The answers are those checked on 2026-11-16 (downloads plan, Task 1).
    private fun answer(code: Int, body: String = "") = Response.Builder()
        .request(Request.Builder().url("https://books.example/api/v1/audiobooks/1/assets/a/content").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Refused")
        .body(body.toResponseBody(Json))
        .build()

    @Test
    fun `a refusal is told as the app tells the server's other refusals`() {
        val missing = """{"statusCode":403,"message":"Missing permission: library_download"}"""

        assertThat(refusals.refusal(answer(403, missing))).isEqualTo(ApiError.Unexpected)
        assertThat(refusals.refusal(answer(404))).isEqualTo(ApiError.NotFound)
        assertThat(refusals.refusal(answer(500))).isEqualTo(ApiError.ServerFailed)
        assertThat(refusals.refusal(answer(401))).isEqualTo(ApiError.SessionExpired)
    }

    @Test
    fun `a book merged into another is told with the book it went to, read from the answer's body`() {
        val merged = """{"statusCode":404,"errorCode":"BOOK_MERGED","errorMeta":{"targetBookId":9}}"""

        assertThat(refusals.refusal(answer(404, merged))).isEqualTo(ApiError.Moved(BookId("9")))
    }

    @Test
    fun `the body is only peeked at, the answer left as it came`() {
        val missing = """{"statusCode":403,"message":"Missing permission: library_download"}"""
        val response = answer(403, missing)

        refusals.refusal(response)

        assertThat(response.body.string()).isEqualTo(missing)
    }
}
