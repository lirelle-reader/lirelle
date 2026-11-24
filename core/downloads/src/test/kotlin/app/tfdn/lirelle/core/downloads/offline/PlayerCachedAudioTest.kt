package app.tfdn.lirelle.core.downloads.offline

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import com.google.common.truth.Truth.assertThat
import java.io.File
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Key = "aud_1:e1"
private const val Content = "0123456789abcdefghij"

/** The player's cache as a source of an audiobook's files: only one it holds whole is taken from it. */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class PlayerCachedAudioTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cache by lazy { AudioCache(context, File(folder.root, "audio")) }
    private val cached by lazy { PlayerCachedAudio(cache) }
    private val into: File by lazy { File(folder.newFolder("downloads"), "0-aud_1") }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun planned(
        cacheKey: String? = Key,
        expectedBytes: Long? = Content.length.toLong(),
        kind: FileKind = FileKind.Audio,
    ): PlannedFile =
        PlannedFile("0-aud_1", FileSource.AudioAsset("aud_1"), "e1", expectedBytes, resumable = true, kind, cacheKey)

    /** The player streams the file, as far as [bytes] (all of it when null), through its cache. */
    private fun played(bytes: Int? = null) {
        server.enqueue(MockResponse(body = Content))
        val source = cache.dataSourceFactory(OkHttpClient()).createDataSource()
        source.open(DataSpec.Builder().setUri(server.url("/assets/aud_1/content").toString()).setKey(Key).build())
        if (bytes == null) DataSourceUtil.readToEnd(source) else source.read(ByteArray(bytes), 0, bytes)
        source.close()
    }

    @Test
    fun `a file the player's cache holds whole is copied from it, without a request`() {
        played()

        assertThat(cached.copyOf(planned(), into)).isTrue()

        assertThat(into.readText()).isEqualTo(Content)
        assertThat(into.parentFile?.list()).asList().containsExactly(into.name)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a size the plan does not know is no obstacle`() {
        played()

        assertThat(cached.copyOf(planned(expectedBytes = null), into)).isTrue()
        assertThat(into.readText()).isEqualTo(Content)
    }

    @Test
    fun `a file the cache holds in part is not copied`() {
        played(bytes = 5)
        assertThat(cache.cache.getCachedBytes(Key, 0, Content.length.toLong())).isGreaterThan(0)

        assertThat(cached.copyOf(planned(), into)).isFalse()

        assertThat(into.parentFile?.list()).asList().isEmpty()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a file the cache does not hold, or holds in another version or size, is not copied`() {
        assertThat(cached.copyOf(planned(), into)).isFalse()
        played()

        assertThat(cached.copyOf(planned(cacheKey = "aud_1:e2"), into)).isFalse()
        assertThat(cached.copyOf(planned(expectedBytes = 99), into)).isFalse()

        assertThat(into.exists()).isFalse()
    }

    @Test
    fun `a narration track, which has no key of its own, and an EPUB are not copied`() {
        played()

        assertThat(cached.copyOf(planned(cacheKey = null), into)).isFalse()
        assertThat(cached.copyOf(planned(kind = FileKind.Epub), into)).isFalse()

        assertThat(into.exists()).isFalse()
    }

    @Test
    fun `a copy that cannot be written is not one, and leaves nothing`() {
        played()
        val nowhere = File(folder.root, "missing/0-aud_1")

        assertThat(cached.copyOf(planned(), nowhere)).isFalse()

        assertThat(nowhere.parentFile?.exists()).isFalse()
    }
}
