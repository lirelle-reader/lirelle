package app.tfdn.lirelle.core.audio.cache

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import java.io.File
import okhttp3.OkHttpClient

private const val IndexVersion = 1

/**
 * The index of one cache, in a file of its own beside the cache's folder. Media3's own provider keeps one
 * database for the whole application: the index of a profile's cache, which names what was listened to, would
 * stay on the device after the profile's folders are deleted.
 */
@UnstableApi
private class CacheIndex(context: Context, file: File) :
    SQLiteOpenHelper(context, file.path, null, IndexVersion),
    DatabaseProvider {

    /** The cache makes its own tables. */
    override fun onCreate(db: SQLiteDatabase) = Unit

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/**
 * The audio files already streamed, kept in the cache area of a profile so that a replayed passage, a
 * "back 15 s" or a short network loss does not go to the server again. Least recently played first out, 1 GB
 * at most.
 *
 * Entries are keyed `assetId:etag` ([key]): a file that changed on the server has another etag, so it is
 * fetched again. The cache is created on first use, once for its directory, which it locks until [close]. Its
 * index is the file `<directory>.db` beside it: both are the profile's, and go with its folder.
 */
@UnstableApi
class AudioCache(
    private val context: Context,
    private val directory: File,
    val limitBytes: Long = LimitBytes,
) {
    private var current: SimpleCache? = null
    private var index: CacheIndex? = null
    private var closed = false

    /**
     * The one [SimpleCache] of [directory].
     * Making it opens the database and scans the files: the first call blocks, so make it off the main thread.
     *
     * @throws IllegalStateException once [close]d: the profile is gone, and so is what it cached.
     */
    val cache: SimpleCache
        get() = checkNotNull(cacheOrNull()) { "The audio cache is closed" }

    /** Null once [close]d: never made again then, which would make again the folder of a profile that closed. */
    @Synchronized
    private fun cacheOrNull(): SimpleCache? = if (closed) null else current ?: open().also { current = it }

    private fun open(): SimpleCache {
        directory.mkdirs()
        val index = CacheIndex(context, File(directory.path + IndexSuffix)).also { index = it }
        return SimpleCache(directory, LeastRecentlyUsedCacheEvictor(limitBytes), index)
    }

    /**
     * Reads the file the device keeps of a download ([downloaded]) when there is one, without the cache nor the
     * network; otherwise through the cache, from the server with [client] (the authenticated one) on a miss.
     * Once the cache is [close]d, from the server alone.
     */
    fun dataSourceFactory(
        client: OkHttpClient,
        downloaded: DownloadedFiles = DownloadedFiles.None,
    ): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(client)
        // The cache is looked up for each data source: one asked for after the close() must not use the released one.
        return DataSource.Factory {
            val cached = cacheOrNull()?.let { cache ->
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setCacheKeyFactory(keyFactory)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
            }
            DownloadedAudioSource(downloaded, cached ?: upstream.createDataSource())
        }
    }

    /**
     * The profile closes: lets go of the folder's lock and of the index, for good, so that the folder can be
     * deleted and the same profile opened again in this process. What the cache holds stays in its folder.
     *
     * Call it once nothing plays: a player still reading through a released cache fails.
     */
    @Synchronized
    fun close() {
        closed = true
        current?.release()
        current = null
        index?.close()
        index = null
    }

    companion object {
        const val LimitBytes = 1024L * 1024 * 1024

        private const val IndexSuffix = ".db"

        /** The cache key of a file of a book: its asset and its version. */
        fun key(assetId: String, etag: String?): String = "$assetId:${etag.orEmpty()}"

        /** The key set on the media item when there is one, the address otherwise. */
        val keyFactory = CacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
    }
}
