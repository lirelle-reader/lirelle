package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.position.WrittenPlace
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * A book ready to play: its playlist, where it starts on its timeline and at what speed, and what it is: the
 * audiobook, or the narration of a read-along file.
 *
 * @property kept it plays from its download: the device has this format whole.
 */
data class LoadedBook(
    val bookId: BookId,
    val playlist: BookPlaylist,
    val seriesId: SeriesId?,
    val startMs: Long,
    val speed: Float,
    val source: BookSource = BookSource.Audiobook,
    val kept: Boolean = false,
)

/**
 * Reads what playing a book needs: its manifest, its page (cover, series), where to start and the speed.
 * The cover is the audiobook's own when the book has one, the book's otherwise; for an audiobook played from its
 * download, the one of them kept on the device, in the same order, which needs no network.
 *
 * It starts at the server's position, whose version becomes the one the next writes are made over, unless this
 * device's own latest position (kept on the device) is to win:
 * - a state written elsewhere stands unanswered against this device's writes ([standing]): the device's
 *   place opens, the other one stays offered, nothing is sent until an answer;
 * - otherwise the kept position is newer than the server's, and the server's was not chosen by the user
 *   ("Go there", or a state at the device's place followed without a word: its version is the known
 *   one, and it is not this device's own write, and nothing of the book waits to be sent).
 * Without the server, at the kept position; with neither, at the beginning.
 *
 * An audiobook the device has whole ([kept]) plays the files of the manifest its download kept: that manifest
 * is the one read when the server cannot give its own (it cannot be reached, or no longer has the book) and
 * when the server's is another content version, whose files are not the ones on the device. The page kept likewise
 * stands in for the server's. Any other book is read from the server alone.
 *
 * @param waiting whether a position of the book still waits to be sent.
 * @param standing whether a state written elsewhere stands unanswered against the book's positions.
 * @param kept what the device keeps of the books; null when it keeps nothing.
 */
class BookLoader(
    private val audio: AudioRepository,
    private val library: LibraryRepository,
    private val store: PlaybackStore,
    private val settings: PlayerSettingsStore,
    private val waiting: suspend (bookId: BookId) -> Boolean = { false },
    private val standing: suspend (bookId: BookId) -> Boolean = { false },
    private val kept: KeptBooks? = null,
    private val partLabel: (Int) -> String,
) {
    /** Null when the manifest cannot be read, or the book has no file to play. [at] overrides the start. */
    suspend fun load(bookId: BookId, fromStart: Boolean, at: Long? = null): LoadedBook? = coroutineScope {
        // The three requests at once: the server answers each in its own time.
        val manifestAsked = async { audio.manifest(bookId) }
        val pageAsked = async { library.book(bookId) }
        val serverAsked = if (at == null && !fromStart) {
            async { (audio.playbackState(bookId) as? ApiResult.Success)?.value }
        } else {
            null
        }
        val copy = kept?.copyOf(bookId, DownloadFormat.Audiobook)
        val manifest = manifestOf(bookId, manifestAsked.await(), copy)
            ?: return@coroutineScope null.also { coroutineContext.cancelChildren() }
        val page = pageAsked.await().let { answer ->
            (answer as? ApiResult.Success)?.value ?: copy?.takeIf { answer.isMissing }?.detail(bookId)
        }
        val playlist = playlistOf(bookId, manifest, page, copy?.audiobookCoverAddress(bookId))
            ?: return@coroutineScope null.also { coroutineContext.cancelChildren() }
        val start = at ?: if (serverAsked == null) 0 else startOf(bookId, playlist, serverAsked.await())
        val speed = settings.speedOf(bookId) ?: settings.settings.first().lastSpeed
        LoadedBook(bookId, playlist, page?.series?.id, start, speed, kept = copy != null)
    }

    /**
     * The cover is the one [onDevice] for a download, the audiobook's own before the book's; else the server's,
     * in the same order.
     */
    private fun playlistOf(
        bookId: BookId,
        manifest: AudioManifest,
        page: BookDetail?,
        onDevice: String?,
    ): BookPlaylist? {
        val cover = page?.audioCover
        return BookPlaylist.of(
            manifest = manifest,
            coverUrl = onDevice ?: cover?.url ?: page?.coverUrl,
            partLabel = partLabel,
            artworkUrl = onDevice ?: cover?.thumbnailUrl ?: page?.coverUrl,
        ) { audio.assetUrl(bookId, it) }
    }

    /**
     * The server's manifest; the one [copy] kept when the server cannot give its own, or gives another content
     * version than the one whose files are on the device.
     */
    private suspend fun manifestOf(
        bookId: BookId,
        answer: ApiResult<AudioManifest>,
        copy: KeptBookData?,
    ): AudioManifest? {
        val served = (answer as? ApiResult.Success)?.value
        if (copy == null || (served == null && !answer.isMissing)) return served
        val keptManifest = copy.manifest(bookId) ?: return served
        return served?.takeIf { it.contentVersion == keptManifest.contentVersion } ?: keptManifest
    }

    private suspend fun startOf(bookId: BookId, playlist: BookPlaylist, server: PlaybackState?): Long {
        val kept = store.get(bookId)
        val serverTime = server?.let { playlist.timeline.bookTimeOf(it.assetId, it.positionMs) }
        val keptWins = kept != null && (server == null || keepsOwn(bookId, kept, server))
        return when {
            server != null && serverTime != null && !keptWins -> {
                store.setKnownVersion(bookId, server.version)
                serverTime
            }
            kept != null -> kept.bookTimeMs
            else -> 0
        }
    }

    /** Whether this device's [kept] position opens rather than [server]'s. */
    private suspend fun keepsOwn(bookId: BookId, kept: LocalPlayback, server: PlaybackState): Boolean {
        if (standing(bookId)) return true
        val chosen = server.version == store.knownVersion(bookId) && !waiting(bookId) && !isOurs(bookId, server)
        return !chosen && (server.capturedAt?.let(kept.at::isAfter) ?: true)
    }

    /** Whether [server] is this device's own write: at one of its places, or captured no later than its last. */
    private suspend fun isOurs(bookId: BookId, server: PlaybackState): Boolean {
        if (WrittenPlace(server.assetId, server.positionMs) in store.writtenPlaces(bookId)) return true
        val written = store.lastWritten(bookId) ?: return false
        return server.capturedAt?.isAfter(written) == false
    }
}
