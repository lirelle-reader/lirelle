package app.tfdn.lirelle.core.audio.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class AudioCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cache(limit: Long = 1024) = AudioCache(context, File(folder.root, "audio"), limit)

    @Test
    fun `the key is the asset and its etag`() {
        assertThat(AudioCache.key(assetId = "aud_12", etag = "abc")).isEqualTo("aud_12:abc")
    }

    @Test
    fun `a changed etag is another key, so the file is fetched again`() {
        assertThat(AudioCache.key("aud_12", "abc")).isNotEqualTo(AudioCache.key("aud_12", "def"))
        assertThat(AudioCache.key("aud_12", null)).isEqualTo("aud_12:")
    }

    @Test
    fun `the cache key factory reads the key set on the media item`() {
        val spec = DataSpec.Builder().setUri("https://example.org/a").setKey("12:abc").build()
        assertThat(AudioCache.keyFactory.buildCacheKey(spec)).isEqualTo("12:abc")
    }

    @Test
    fun `the cache is limited to a gigabyte, least recently used first`() {
        assertThat(AudioCache.LimitBytes).isEqualTo(1024L * 1024 * 1024)
        val cache = cache(limit = 4096)
        assertThat(cache.limitBytes).isEqualTo(4096)
        assertThat(cache.cache.cacheSpace).isEqualTo(0)
    }

    @Test
    fun `there is one cache for a directory, made on first use`() {
        val cache = cache()
        assertThat(cache.cache).isSameInstanceAs(cache.cache)
        assertThat(File(folder.root, "audio").exists()).isTrue()
    }

    @Test
    fun `the data source reads through the cache`() {
        val factory = cache().dataSourceFactory(OkHttpClient())
        assertThat(factory).isInstanceOf(DataSource.Factory::class.java)
    }

    /** The index names what was listened to: it is the profile's as the files are, and goes with its folder. */
    @Test
    fun `the index of the cache is a file beside its folder, not a database of the application`() {
        val cache = cache()
        cache.put("aud_1:v1", "chapter".toByteArray())

        assertThat(File(folder.root, "audio.db").isFile).isTrue()
        assertThat(context.databaseList().toList()).isEmpty()
    }

    @Test
    fun `closing lets go of the folder, which another cache can then take, with what it held`() {
        val cache = cache()
        cache.put("aud_1:v1", "chapter".toByteArray())

        cache.close()

        // A SimpleCache refuses a folder that another one holds.
        val again = cache()
        assertThat(again.cache.getCachedBytes("aud_1:v1", 0, 7)).isEqualTo(7)
        again.close()
    }

    @Test
    fun `once closed, the cache is not made again, nor the folder of its profile`() {
        val cache = cache()
        cache.cache
        cache.close()
        folder.root.deleteRecursively()

        val failure = runCatching { cache.cache }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(folder.root.exists()).isFalse()
    }

    @Test
    fun `a cache closed before its first use is not made either`() {
        val cache = cache()

        cache.close()

        assertThat(runCatching { cache.cache }.isFailure).isTrue()
        assertThat(File(folder.root, "audio").exists()).isFalse()
    }

    private fun AudioCache.put(key: String, bytes: ByteArray) {
        val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
        val file = cache.startFile(key, 0, bytes.size.toLong())
        file.writeBytes(bytes)
        cache.commitFile(file, bytes.size.toLong())
        cache.releaseHoleSpan(hole)
    }

    @Test
    fun `a factory reads what the cache holds without the server`() = runTest {
        val cache = cache()
        val factory = cache.dataSourceFactory(OkHttpClient())
        cache.put("aud_1:v1", "chapter".toByteArray())

        val source = factory.createDataSource()
        val spec = DataSpec.Builder().setUri("http://invalid.example/a").setKey("aud_1:v1").setLength(7).build()
        source.open(spec)
        val read = ByteArray(7)
        source.read(read, 0, read.size)
        source.close()

        assertThat(String(read)).isEqualTo("chapter")
    }

    /** A player that outlived the close: it reads from the server, and nothing is made again on the disk. */
    @Test
    fun `a factory made before the close gives a source that leaves the released cache alone`() {
        val cache = cache()
        val factory = cache.dataSourceFactory(OkHttpClient())
        cache.put("aud_1:v1", "chapter".toByteArray())
        cache.close()
        folder.root.deleteRecursively()

        val source = factory.createDataSource()
        val spec = DataSpec.Builder().setUri("http://127.0.0.1:1/a").setKey("aud_1:v1").setLength(7).build()
        val failure = runCatching { source.open(spec) }.exceptionOrNull()
        source.close()

        // Not the cached bytes, which are gone: the server, which this address does not reach.
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(folder.root.exists()).isFalse()
    }

    @Test
    fun `the least recently used spans go first when the limit is passed`() {
        val cache = cache(limit = 2500)
        val bytes = ByteArray(1000)

        listOf("a", "b", "c").forEach {
            cache.put(it, bytes)
            Thread.sleep(PauseMs)
        }

        assertThat(cache.cache.getCachedBytes("a", 0, 1000)).isEqualTo(0)
        assertThat(cache.cache.getCachedBytes("b", 0, 1000)).isEqualTo(1000)
        assertThat(cache.cache.getCachedBytes("c", 0, 1000)).isEqualTo(1000)
    }

    private companion object {
        const val PauseMs = 20L
    }
}
