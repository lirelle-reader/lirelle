package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import org.junit.Rule
import org.junit.Test

// The answers are those checked on 2026-11-16 (plan, Task 1).
class DownloaderTest {

    @get:Rule
    internal val fixture = DownloaderFixture()

    private val server get() = fixture.server

    @Test
    fun `a whole file is fetched into its place`() = runBlocking {
        val content = bytes(300_000)
        val file = fixture.audio(expectedBytes = content.size.toLong())
        server.enqueue(whole(content).build())

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(fixture.target(file).readBytes()).isEqualTo(content)
        assertThat(fixture.part(file).exists()).isFalse()
        assertThat(fixture.partVersion(file).exists()).isFalse()
        assertThat(server.takeRequest().headers["Range"]).isNull()
    }

    @Test
    fun `a connection lost on the way is a cut, and what came is kept`() = runBlocking {
        val file = fixture.audio()
        server.enqueue(whole(bytes(4_000_000)).onResponseBody(SocketEffect.ShutdownConnection).build())

        val result = fixture.fetch(file)

        val kept = fixture.part(file).length()
        assertThat(result).isEqualTo(FetchResult.Cut(kept, ApiError.ServerUnreachable))
        // The network's failure: no answer of the server for its source to read.
        assertThat(fixture.refusals.read).isEmpty()
        assertThat(kept).isGreaterThan(0)
        assertThat(kept).isLessThan(4_000_000)
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `a stream that ends before the expected length is a cut`() = runBlocking {
        val file = fixture.audio(expectedBytes = 300_000)
        // No length announced: the stream just ends.
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(bytes(100_000)), 8_192).build())

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Cut(100_000, ApiError.Unexpected))
        assertThat(fixture.part(file).length()).isEqualTo(100_000)
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `a refusal of the server is what its source reads in the answer`() = runBlocking {
        val file = fixture.audio()
        val json = headersOf("Content-Type", "application/json")
        val refusal = """{"statusCode":403,"message":"Missing permission: library_download"}"""
        server.enqueue(MockResponse(code = 403, headers = json, body = refusal))
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 500))

        val results = List(3) { fixture.fetch(file) }

        assertThat(results).containsExactly(
            FetchResult.Refused(DownloadFailure.Server(ApiError.Unexpected)),
            FetchResult.Refused(DownloadFailure.Server(ApiError.NotFound)),
            FetchResult.Refused(DownloadFailure.Server(ApiError.ServerFailed)),
        ).inOrder()
        assertThat(fixture.refusals.read).containsExactly(403 to refusal, 404 to "", 500 to "").inOrder()
        assertThat(fixture.part(file).exists()).isFalse()
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `a refusal its source reads as the account's rights fails as not allowed`() = runBlocking {
        val file = fixture.audio()
        fixture.refusals.notAllowed = 403
        server.enqueue(MockResponse(code = 403))

        assertThat(fixture.fetch(file)).isEqualTo(FetchResult.Refused(DownloadFailure.NotAllowed))
        assertThat(fixture.part(file).exists()).isFalse()
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `a cancelled download leaves what came and no file in place`() = runBlocking {
        val file = fixture.audio()
        server.enqueue(whole(bytes(2_000_000)).throttleBody(16_384, 20, TimeUnit.MILLISECONDS).build())
        val started = CompletableDeferred<Unit>()
        fixture.now = System::currentTimeMillis

        val job = launch(Dispatchers.Default) { fixture.fetch(file) { if (it > 0) started.complete(Unit) } }
        started.await()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(fixture.part(file).length()).isGreaterThan(0)
        assertThat(fixture.part(file).length()).isLessThan(2_000_000)
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `a download waiting for the server stops when it is cancelled`() = runBlocking {
        val file = fixture.audio()
        server.enqueue(whole(bytes(10)).headersDelay(30, TimeUnit.SECONDS).build())

        val job = launch(Dispatchers.Default) { fixture.fetch(file) }
        server.takeRequest()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `progress is told at the start and at the end when the file comes at once`() = runBlocking {
        val file = fixture.audio()
        server.enqueue(whole(bytes(300_000)).build())
        val told = mutableListOf<Long>()

        fixture.fetch(file) { told += it }

        assertThat(told).containsExactly(0L, 300_000L).inOrder()
    }

    @Test
    fun `progress is told again once a quarter of a second has passed`() = runBlocking {
        val file = fixture.audio()
        server.enqueue(whole(bytes(1_000_000)).build())
        val told = mutableListOf<Long>()
        var clock = 0L
        // Each block read takes 125 ms of this clock.
        fixture.now = { clock.also { clock += 125 } }

        fixture.fetch(file) { told += it }
        val blocks = clock.toInt() / 125

        assertThat(told.size).isGreaterThan(2)
        assertThat(told.size).isAtMost(blocks / 2 + 2)
        assertThat(told).isInOrder()
        assertThat(told.last()).isEqualTo(1_000_000L)
    }

    @Test
    fun `a device with no room left stops the download and keeps what came`() = runBlocking {
        val file = fixture.audio(expectedBytes = 300_000)
        server.enqueue(whole(bytes(300_000)).build())
        fixture.freeBytes = 1_000_000

        val result = fixture.fetch(file)

        // What is still to come, and the room the device must keep, less the room there is.
        val missing = 300_000L + 16L * 1024 * 1024 - 1_000_000
        assertThat(result).isEqualTo(FetchResult.Refused(DownloadFailure.NoSpace(missing)))
        assertThat(fixture.part(file).exists()).isTrue()
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `a full device is known by what the system says of it`() {
        val full = IOException("write failed", IOException("write failed: ENOSPC (No space left on device)"))

        assertThat(full.saysNoSpace()).isTrue()
        assertThat(IOException("No space left on device").saysNoSpace()).isTrue()
        assertThat(IOException("Connection reset").saysNoSpace()).isFalse()
    }
}
