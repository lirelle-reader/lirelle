package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ErrorMapperTest {

    private val mapper = ErrorMapper(ApiJson)

    private fun http(code: Int, body: String = "{}"): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `network failures mean the server is unreachable`() {
        assertThat(mapper.map(UnknownHostException("nope"), CallKind.Probe)).isEqualTo(ApiError.ServerUnreachable)
        assertThat(mapper.map(SSLHandshakeException("bad cert"), CallKind.Login)).isEqualTo(ApiError.ServerUnreachable)
        assertThat(mapper.map(IOException("timeout"), CallKind.Authenticated)).isEqualTo(ApiError.ServerUnreachable)
    }

    @Test
    fun `a connection that could not be made never sent the request`() {
        assertThat(mapper.neverSent(UnknownHostException("no network"))).isTrue()
        assertThat(mapper.neverSent(ConnectException("refused"))).isTrue()
        assertThat(mapper.neverSent(NoRouteToHostException("no route"))).isTrue()
        assertThat(mapper.neverSent(SSLHandshakeException("bad cert"))).isTrue()
        assertThat(mapper.neverSent(SocketTimeoutException("connect timed out"))).isTrue()
        assertThat(mapper.neverSent(IOException("wrapped", ConnectException("refused")))).isTrue()
    }

    @Test
    fun `a failure once connected may have reached the server`() {
        assertThat(mapper.neverSent(SocketTimeoutException("timeout"))).isFalse()
        assertThat(mapper.neverSent(SocketTimeoutException("Read timed out"))).isFalse()
        assertThat(mapper.neverSent(SocketException("Connection reset"))).isFalse()
        assertThat(mapper.neverSent(EOFException("unexpected end of stream"))).isFalse()
        assertThat(mapper.neverSent(IOException("timeout"))).isFalse()
    }

    @Test
    fun `a call says whether its request may have reached the server, the error staying the same`() = runBlocking {
        val refused = mapper.call(CallKind.Authenticated) { throw ConnectException("refused") } as ApiResult.Failure
        val unanswered = mapper.call(CallKind.Authenticated) { throw SocketTimeoutException("timeout") }
            as ApiResult.Failure

        assertThat(refused.mayHaveReachedServer).isFalse()
        assertThat(unanswered.mayHaveReachedServer).isTrue()
        assertThat(refused).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(unanswered.error).isEqualTo(ApiError.ServerUnreachable)
        assertThat(ApiResult.Failure(ApiError.ServerFailed).mayHaveReachedServer).isTrue()
    }

    @Test
    fun `a probe answered by something else is not a BookOrbit server`() {
        assertThat(mapper.map(http(404), CallKind.Probe)).isEqualTo(ApiError.UnknownServer)
        assertThat(mapper.map(SerializationException("html"), CallKind.Probe)).isEqualTo(ApiError.UnknownServer)
    }

    @Test
    fun `a server error means the server failed, whatever the call`() {
        for (kind in CallKind.entries) {
            for (code in listOf(500, 502, 503)) {
                assertThat(mapper.map(http(code), kind)).isEqualTo(ApiError.ServerFailed)
            }
        }
    }

    @Test
    fun `409 on an authenticated call is a busy server, worth another try`() {
        val body = """{"statusCode":409,"message":"Too many builds","errorCode":"DELIVERY_BUSY"}"""

        assertThat(mapper.map(http(409, body), CallKind.Authenticated)).isEqualTo(ApiError.ServerFailed)
        assertThat(mapper.map(http(409), CallKind.Authenticated)).isEqualTo(ApiError.ServerFailed)
    }

    @Test
    fun `a refused request is not a server failure`() {
        assertThat(mapper.map(http(400), CallKind.Authenticated)).isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(http(403), CallKind.Authenticated)).isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(http(400), CallKind.Other)).isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(http(409), CallKind.Probe)).isEqualTo(ApiError.UnknownServer)
        assertThat(mapper.map(http(409), CallKind.OidcCallback)).isEqualTo(ApiError.OidcRefused)
    }

    @Test
    fun `403 password change required on an authenticated call means the default password must change`() {
        val body = """{"statusCode":403,"message":"Password change required","timestamp":"2026-11-13T08:00:00.000Z"}"""

        assertThat(mapper.map(http(403, body), CallKind.Authenticated)).isEqualTo(ApiError.DefaultPasswordMustChange)
    }

    @Test
    fun `any other 403 on an authenticated call is unexpected`() {
        val body = """{"statusCode":403,"message":"Forbidden resource"}"""

        assertThat(mapper.map(http(403, body), CallKind.Authenticated)).isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(http(403), CallKind.Authenticated)).isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(http(403, """{"message":["Password change required"]}"""), CallKind.Authenticated))
            .isEqualTo(ApiError.Unexpected)
    }

    @Test
    fun `401 on login means wrong credentials`() {
        assertThat(mapper.map(http(401), CallKind.Login)).isEqualTo(ApiError.InvalidCredentials)
    }

    @Test
    fun `locked account carries the wait time`() {
        val error = mapper.map(http(401, Fixtures.read("error-account-locked.json")), CallKind.Login)

        assertThat(error).isEqualTo(ApiError.AccountLocked(retryAfterSeconds = 540))
    }

    @Test
    fun `429 means too many attempts whatever the call`() {
        assertThat(mapper.map(http(429), CallKind.Login)).isEqualTo(ApiError.TooManyAttempts)
        assertThat(mapper.map(http(429), CallKind.OidcCallback)).isEqualTo(ApiError.TooManyAttempts)
    }

    @Test
    fun `client errors on the oidc callback mean the sign-in was refused`() {
        assertThat(mapper.map(http(401), CallKind.OidcCallback)).isEqualTo(ApiError.OidcRefused)
        assertThat(mapper.map(http(400), CallKind.OidcCallback)).isEqualTo(ApiError.OidcRefused)
    }

    @Test
    fun `401 on an authenticated call means the session expired`() {
        assertThat(mapper.map(http(401), CallKind.Authenticated)).isEqualTo(ApiError.SessionExpired)
    }

    @Test
    fun `everything else is unexpected`() {
        assertThat(mapper.map(http(403), CallKind.Authenticated)).isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(http(400, Fixtures.read("error-validation.json")), CallKind.Login))
            .isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(SerializationException("bad json"), CallKind.Login)).isEqualTo(ApiError.Unexpected)
        assertThat(mapper.map(IllegalStateException("bug"), CallKind.Other)).isEqualTo(ApiError.Unexpected)
    }

    @Test
    fun `404 on an authenticated call means the thing is gone`() {
        assertThat(mapper.map(http(404), CallKind.Authenticated)).isEqualTo(ApiError.NotFound)
    }

    @Test
    fun `a merged book reports the book that replaces it`() {
        val body = """{"statusCode":404,"message":"Book 12 was merged","errorCode":"BOOK_MERGED",""" +
            """"errorMeta":{"targetBookId":34}}"""

        assertThat(mapper.map(http(404, body), CallKind.Authenticated)).isEqualTo(ApiError.Moved(BookId("34")))
    }

    @Test
    fun `a merged book without a target is simply gone`() {
        val body = """{"statusCode":404,"errorCode":"BOOK_MERGED","errorMeta":{}}"""

        assertThat(mapper.map(http(404, body), CallKind.Authenticated)).isEqualTo(ApiError.NotFound)
    }

    @Test
    fun `an explicit api error passes through`() {
        assertThat(mapper.map(ApiErrorException(ApiError.DefaultPasswordMustChange), CallKind.Login))
            .isEqualTo(ApiError.DefaultPasswordMustChange)
    }

    @Test
    fun `call wraps success and failure`() = runBlocking {
        assertThat(mapper.call(CallKind.Other) { 7 }).isEqualTo(ApiResult.Success(7))
        assertThat(mapper.call<Int>(CallKind.Login) { throw http(401) })
            .isEqualTo(ApiResult.Failure(ApiError.InvalidCredentials))
    }

    @Test
    fun `call never swallows cancellation`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { mapper.call<Int>(CallKind.Other) { throw CancellationException("cancelled") } }
        }
    }

    @Test
    fun `a call that could not reach the server tells its kind, its failure and whether it never left`() =
        runBlocking<Unit> {
            val told = mutableListOf<String>()
            val mapper = ErrorMapper(ApiJson) { NetworkLog { told += it } }

            mapper.call(CallKind.Authenticated) { throw IOException("timeout", ConnectException("refused")) }
            mapper.call(CallKind.Authenticated) { "fine" }

            assertThat(told).containsExactly(
                "Unreachable (Authenticated): IOException: timeout, causes [ConnectException], never sent: true",
            )
        }
}
