package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Rule
import org.junit.Test

/** A page received is kept only when it is a picture: checked by its first bytes, never decoded. */
class DownloaderPagesTest {

    @get:Rule
    internal val fixture = DownloaderFixture()

    private val page: PlannedFile = pageFiles(FileId("12"), PagedKind.Comic, 4, "v1")[3]

    private fun answer(bytes: ByteArray) {
        fixture.server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(bytes), maxChunkSize = 64).build())
    }

    private suspend fun fetched(bytes: ByteArray): FetchResult {
        answer(bytes)
        return fixture.fetch(page, DownloadFormat.Text)
    }

    private fun kept(): File = fixture.target(page, DownloadFormat.Text)

    private fun left(): List<String> = kept().parentFile?.list().orEmpty().toList()

    private fun bytesOf(vararg start: Int, more: Int = 200): ByteArray =
        start.map { it.toByte() }.toByteArray() + bytes(more)

    @Test
    fun `a JPEG, a PNG, a WebP and a GIF are pages`() = runBlocking<Unit> {
        val riff = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + bytes(200)
        val pictures = mapOf(
            "JPEG" to bytesOf(0xFF, 0xD8, 0xFF, 0xE0),
            "PNG" to bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "WebP" to riff,
            "GIF" to "GIF89a".toByteArray() + bytes(200),
        )
        pictures.forEach { (name, picture) ->
            kept().delete()

            assertThat(fetched(picture)).isEqualTo(FetchResult.Done)

            assertThat(kept().readBytes()).isEqualTo(picture)
            assertThat(left()).containsExactly("3")
            assertThat(fixture.server.takeRequest().url.encodedPath)
                .isEqualTo("/pages/comic/12/3")
            assertThat(name).isNotEmpty()
        }
        assertThat(kept().parentFile?.name).isEqualTo("pages")
    }

    @Test
    fun `what is no picture is refused as a bad file is, and nothing of it is kept`() = runBlocking<Unit> {
        val others = listOf(
            """{"statusCode":500,"message":"The page could not be drawn"}""".toByteArray(),
            "<html>".toByteArray(),
            // A sound file starts as a WebP does, and is none.
            "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + bytes(200),
            bytesOf(0xFF, 0xD8, more = 0),
            bytes(300),
        )
        others.forEach { other ->
            assertThat(fetched(other)).isEqualTo(FetchResult.Refused(DownloadFailure.NotAnEpub))
            assertThat(left()).isEmpty()
        }
    }

    @Test
    fun `an empty page is refused`() = runBlocking<Unit> {
        fixture.server.enqueue(MockResponse.Builder().build())

        assertThat(fixture.fetch(page, DownloadFormat.Text)).isEqualTo(FetchResult.Refused(DownloadFailure.NotAnEpub))

        assertThat(left()).isEmpty()
    }

    @Test
    fun `a page asked again starts again, the server answering no range for it`() = runBlocking<Unit> {
        fixture.part(page, DownloadFormat.Text).apply { checkNotNull(parentFile).mkdirs() }.writeBytes(bytes(40))
        val picture = jpeg(500)

        assertThat(fetched(picture)).isEqualTo(FetchResult.Done)

        assertThat(fixture.server.takeRequest().headers["Range"]).isNull()
        assertThat(kept().readBytes()).isEqualTo(picture)
        assertThat(left()).containsExactly("3")
    }
}
