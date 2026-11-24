package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Rule
import org.junit.Test

class DownloaderResumeTest {

    @get:Rule
    internal val fixture = DownloaderFixture()

    private val server get() = fixture.server
    private val content = bytes(4_000_000)

    @Test
    fun `a cut download goes on from the bytes received`() = runBlocking {
        val served = RangedFile(content, cuts = 1)
        server.dispatcher = served
        val file = fixture.audio(expectedBytes = content.size.toLong())
        val told = mutableListOf<Long>()

        val first = fixture.fetch(file)
        val received = fixture.part(file).length()
        val second = fixture.fetch(file) { told += it }

        assertThat(first).isEqualTo(FetchResult.Cut(received, ApiError.ServerUnreachable))
        assertThat(second).isEqualTo(FetchResult.Done)
        assertThat(served.ranges).containsExactly(null, "bytes=$received-").inOrder()
        assertThat(fixture.target(file).readBytes()).isEqualTo(content)
        assertThat(told.first()).isEqualTo(received)
        assertThat(told.last()).isEqualTo(content.size.toLong())
        assertThat(fixture.part(file).exists()).isFalse()
        assertThat(fixture.partVersion(file).exists()).isFalse()
    }

    @Test
    fun `a server that ignores the range sends the file again from its start`() = runBlocking {
        val served = RangedFile(content, honoursRanges = false)
        server.dispatcher = served
        val file = fixture.audio()
        fixture.leavePart(file, bytes(1_000))

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(served.ranges).containsExactly("bytes=1000-")
        assertThat(fixture.target(file).readBytes()).isEqualTo(content)
    }

    @Test
    fun `what was received of another version is discarded`() = runBlocking {
        val served = RangedFile(content)
        server.dispatcher = served
        val file = fixture.audio(version = "v2")
        fixture.leavePart(file, ByteArray(1_000), version = "v1")

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(served.ranges).containsExactly(null)
        assertThat(fixture.target(file).readBytes()).isEqualTo(content)
    }

    @Test
    fun `what was received of nobody knows which version is discarded`() = runBlocking {
        val served = RangedFile(content)
        server.dispatcher = served
        val file = fixture.audio()
        fixture.leavePart(file, ByteArray(1_000), version = null)

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(served.ranges).containsExactly(null)
        assertThat(fixture.target(file).readBytes()).isEqualTo(content)
    }

    @Test
    fun `a file without ranges starts again`() = runBlocking {
        val served = RangedFile(content)
        server.dispatcher = served
        val file = fixture.audio(resumable = false)
        fixture.leavePart(file, ByteArray(1_000))

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(served.ranges).containsExactly(null)
        assertThat(fixture.target(file).readBytes()).isEqualTo(content)
    }

    @Test
    fun `a file all received before it was put in place asks the server nothing`() = runBlocking {
        val file = fixture.audio(expectedBytes = content.size.toLong())
        fixture.leavePart(file, content)

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Done)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(fixture.target(file).readBytes()).isEqualTo(content)
        assertThat(fixture.partVersion(file).exists()).isFalse()
    }

    @Test
    fun `a range the server cannot give discards what was received`() = runBlocking {
        val file = fixture.audio()
        fixture.leavePart(file, bytes(1_000))
        server.enqueue(MockResponse(code = 416))

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Cut(0, ApiError.Unexpected))
        assertThat(fixture.part(file).exists()).isFalse()
        assertThat(fixture.target(file).exists()).isFalse()
    }

    @Test
    fun `an answer that starts elsewhere than asked is not appended`() = runBlocking {
        val file = fixture.audio()
        fixture.leavePart(file, bytes(1_000))
        server.enqueue(
            MockResponse.Builder().code(206).addHeader("content-range", "bytes 500-999/1000")
                .body(Buffer().write(bytes(500))).build(),
        )

        val result = fixture.fetch(file)

        assertThat(result).isEqualTo(FetchResult.Cut(0, ApiError.Unexpected))
        assertThat(fixture.part(file).exists()).isFalse()
        assertThat(fixture.target(file).exists()).isFalse()
    }
}
