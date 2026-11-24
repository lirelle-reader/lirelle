package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.NetworkFetcher
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.asNetworkClient
import coil3.request.crossfade
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** Disk space for covers. Least recently used images go first when it is full. */
const val CoverDiskCacheBytes: Long = 250L * 1024 * 1024

/**
 * Image loader for covers and portraits.
 *
 * [client] must be the authenticated client: it adds the access token, and only for the
 * session's server. Images are cached on disk under their URL. A book cover URL contains the
 * server's cover version, so a changed cover is a new entry and the old one is never asked for
 * again. The other images (series tiles, portraits) have no version: they are checked with the
 * server as often as its cache headers ask, see [CoverCacheStrategy]. Without the server, what
 * is on the disk is shown, however old.
 *
 * @param directory where the images are kept on the disk. The caller's choice: the covers one account saw
 *   are kept apart from another's.
 */
fun newCoverImageLoader(context: Context, client: OkHttpClient, directory: File): ImageLoader =
    newCoverImageLoader(context, client, directory, now = Clock.System::now)

/** @param now the clock the age of a cached image is measured with. */
@OptIn(ExperimentalCoilApi::class)
internal fun newCoverImageLoader(
    context: Context,
    client: OkHttpClient,
    directory: File,
    now: () -> Instant,
): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(
                NetworkFetcher.Factory(
                    networkClient = { StaleOnFailureClient(client.asNetworkClient()) },
                    cacheStrategy = { CoverCacheStrategy(CacheControlCacheStrategy(now)) },
                ),
            )
        }
        .diskCache {
            DiskCache.Builder()
                .directory(directory.toOkioPath())
                .maxSizeBytes(CoverDiskCacheBytes)
                .build()
        }
        .crossfade(true)
        .build()

/** The loader to use for covers. Null (tests, previews) falls back to Coil's default. */
val LocalLirelleImageLoader = staticCompositionLocalOf<ImageLoader?> { null }
