package app.tfdn.lirelle.core.audio.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import com.google.common.truth.Truth.assertThat
import java.io.File
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The audio files kept, by their address as it was written. */
private class KeptAudio(val files: MutableMap<String, File> = mutableMapOf()) : DownloadedFiles {
    val asked = mutableListOf<String>()

    override suspend fun text(bookId: BookId, fileId: FileId): File? = null

    override fun audio(url: String): File? {
        asked += url
        return files[url]
    }
}

/** What the player reads: the downloaded file of an address first, the cache and the server otherwise. */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class DownloadedAudioSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val kept = KeptAudio()
    private val cache by lazy { AudioCache(context, File(folder.root, "audio")) }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).toString()

    private fun keep(path: String, text: String): String {
        val file = folder.newFile().apply { writeText(text) }
        return url(path).also { kept.files[it] = file }
    }

    private fun read(spec: DataSpec): String {
        val source = cache.dataSourceFactory(OkHttpClient(), kept).createDataSource()
        return try {
            source.open(spec)
            String(DataSourceUtil.readToEnd(source))
        } finally {
            source.close()
        }
    }

    @Test
    fun `a downloaded file is read whole from the device, without a request`() {
        val address = keep("/assets/a1/content?token=t", "0123456789")

        val read = read(DataSpec.Builder().setUri(address).setKey("a1:e1").build())

        assertThat(read).isEqualTo("0123456789")
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(kept.asked).containsExactly(address)
    }

    @Test
    fun `a seek and a clipped segment read their own bytes of the file, without a request`() {
        val address = keep("/track.mp3?fileId=1", "0123456789")

        val fromOffset = read(DataSpec.Builder().setUri(address).setPosition(6).build())
        val segment = read(DataSpec.Builder().setUri(address).setPosition(3).setLength(4).build())

        assertThat(fromOffset).isEqualTo("6789")
        assertThat(segment).isEqualTo("3456")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the length opened is what is left of the file`() {
        val address = keep("/a", "0123456789")
        val source = cache.dataSourceFactory(OkHttpClient(), kept).createDataSource()

        val length = source.open(DataSpec.Builder().setUri(address).setPosition(4).build())
        source.close()

        assertThat(length).isEqualTo(6)
    }

    @Test
    fun `an address that is not downloaded goes to the server once, then to the cache`() {
        keep("/other", "kept")
        server.enqueue(MockResponse(body = "from the server"))
        val spec = DataSpec.Builder().setUri(url("/assets/a2/content")).setKey("a2:e2").build()

        assertThat(read(spec)).isEqualTo("from the server")
        assertThat(read(spec)).isEqualTo("from the server")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `one source reads a downloaded file, then one that is not`() {
        val address = keep("/a", "kept")
        server.enqueue(MockResponse(body = "streamed"))
        val source = cache.dataSourceFactory(OkHttpClient(), kept).createDataSource()

        source.open(DataSpec.Builder().setUri(address).build())
        val first = String(DataSourceUtil.readToEnd(source))
        source.close()
        source.open(DataSpec.Builder().setUri(url("/b")).build())
        val second = String(DataSourceUtil.readToEnd(source))
        source.close()

        assertThat(first).isEqualTo("kept")
        assertThat(second).isEqualTo("streamed")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `without downloads, everything goes through the cache as before`() {
        server.enqueue(MockResponse(body = "streamed"))
        val source = cache.dataSourceFactory(OkHttpClient()).createDataSource()

        source.open(DataSpec.Builder().setUri(url("/b")).build())
        val read = String(DataSourceUtil.readToEnd(source))
        source.close()

        assertThat(read).isEqualTo("streamed")
    }
}
