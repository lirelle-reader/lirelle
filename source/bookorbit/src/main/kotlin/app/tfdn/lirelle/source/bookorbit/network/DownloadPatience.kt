package app.tfdn.lirelle.source.bookorbit.network

import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import okhttp3.Interceptor
import okhttp3.Response

private val DefaultPatience = 5.minutes

/**
 * Waits longer for the first bytes of a book file than for an ordinary answer: BookOrbit's long transfers
 * ([app.tfdn.lirelle.core.network.di.LongTransfer]), told by their paths, as the player and the downloads build
 * their requests from URLs and cannot mark them.
 *
 * The server builds the audio-less copy of a read-along book on its first request: it reads the whole file,
 * which can weigh a gigabyte, before it sends anything. The ordinary time-out gave up after ten seconds. The
 * read-along map is built on its first request too. The audio of a book, an audiobook's files and a narration's
 * tracks, is waited for as long: a download that stalls for a moment goes on instead of being cut.
 */
internal class DownloadPatience(private val patience: Duration = DefaultPatience) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val path = chain.request().url.encodedPath
        val isFile = path.endsWith("/serve") || path.endsWith("/audioless-epub/download") ||
            path.endsWith("/media-overlay") || isAudioAsset(path) || isNarrationTrack(path)
        if (!isFile) return chain.proceed(chain.request())
        val millis = patience.inWholeMilliseconds.toInt()
        return chain.withReadTimeout(millis, TimeUnit.MILLISECONDS).proceed(chain.request())
    }
}

private val AudioAssetPath = Regex(".*/audiobooks/[^/]+/assets/[^/]+/content")

private fun isAudioAsset(path: String) = AudioAssetPath.matches(path)

private val NarrationTrackPath = Regex(".*/epub/[^/]+/media-overlay/file/.+")

private fun isNarrationTrack(path: String) = NarrationTrackPath.matches(path)
