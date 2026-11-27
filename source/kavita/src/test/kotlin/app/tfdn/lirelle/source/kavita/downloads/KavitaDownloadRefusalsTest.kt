package app.tfdn.lirelle.source.kavita.downloads

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * A Kavita server's refusals of a kept file, read from their status as its other refusals to the profile's key; a
 * file refused (403) is the account's rights: its Download role taken away.
 */
class KavitaDownloadRefusalsTest {

    private val refusals = KavitaDownloadRefusals()

    @Test
    fun `a refusal of a file is read from its status`() {
        assertThat(refusals.refusal(response(404))).isEqualTo(ApiError.NotFound)
        assertThat(refusals.refusal(response(503))).isEqualTo(ApiError.ServerFailed)
        assertThat(refusals.refusal(response(429))).isEqualTo(ApiError.TooManyAttempts)
        assertThat(refusals.refusal(response(401))).isEqualTo(ApiError.SessionExpired)
        assertThat(refusals.refusal(response(403))).isEqualTo(ApiError.Unexpected)
    }

    @Test
    fun `a file refused with a 403 is not allowed, any other status is the server's refusal`() {
        assertThat(refusals.failure(response(403))).isEqualTo(DownloadFailure.NotAllowed)
        assertThat(refusals.failure(response(404))).isEqualTo(DownloadFailure.Server(ApiError.NotFound))
        assertThat(refusals.failure(response(401))).isEqualTo(DownloadFailure.Server(ApiError.SessionExpired))
        assertThat(refusals.failure(response(503))).isEqualTo(DownloadFailure.Server(ApiError.ServerFailed))
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://kavita.example.com/api/Download/chapter").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body("".toResponseBody())
        .build()
}
