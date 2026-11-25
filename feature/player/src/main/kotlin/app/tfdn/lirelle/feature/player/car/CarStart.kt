package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.PositionStore
import app.tfdn.lirelle.core.reader.sync.ownPosition
import app.tfdn.lirelle.feature.player.service.LoadRequest
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.player.service.isMissing
import java.time.Instant
import javax.inject.Inject

/**
 * Where the listening of an audiobook stands.
 *
 * @property at when its position last moved, on this device or on the server; null when it was never listened to.
 * @property finished it was listened to its end, and not started again since.
 */
data class AudiobookStanding(val at: Instant?, val finished: Boolean)

/**
 * The book's page marker: where its text is read to, wherever that was last moved.
 *
 * @property cfi the place; null when only a percentage is known of it (written by an e-reader).
 */
data class PageMarker(val cfi: String?, val at: Instant)

/**
 * Where a read-aloud file stands.
 *
 * @property heardAt when the narration's own place last moved; null when it never played.
 * @property marker the page marker of the file; null for a book never opened.
 */
data class NarrationStanding(val heardAt: Instant?, val marker: PageMarker?)

/** Where the listening and the reading of a book stand, on this device and on the server, with their dates. */
interface CarPlaces {
    suspend fun audiobook(bookId: BookId): AudiobookStanding

    suspend fun narration(fileId: FileId): NarrationStanding
}

/**
 * What a book started from the car plays, and from where: a tap starts at once, there is nobody to ask.
 *
 * The one medium the book has, among those its source offers; with both an audiobook and a narration, the one
 * whose position moved last, and the audiobook when neither ever was. A read-aloud book has one position, its
 * page marker, wherever it was moved: reading its text counts as much as hearing it.
 *
 * An audiobook resumes where it was left, a finished one from its start, as on the phone. A narration resumes at
 * its own place, unless the page marker moved since: it then starts at the sentence the marker is in.
 */
class CarStart @Inject constructor(
    private val library: LibraryRepository,
    private val capabilities: SourceCapabilities,
    private val kept: KeptBookData,
    private val places: CarPlaces,
) {
    /** Null when the book has nothing to listen to, or when its page cannot be had. */
    suspend fun requestFor(bookId: BookId): LoadRequest? {
        val page = pageOf(bookId) ?: return null
        val hasAudiobook = capabilities.audiobooks && page.hasAudioFiles
        val readAloud = page.files.firstOrNull { it.hasReadAlong }?.id?.takeIf { capabilities.readAlong }
        return when {
            hasAudiobook && readAloud != null -> lastListened(bookId, readAloud)
            hasAudiobook -> audiobook(bookId, places.audiobook(bookId))
            readAloud != null -> narration(bookId, readAloud, places.narration(readAloud))
            else -> null
        }
    }

    /** The server's page; the one kept with a download when the server cannot give its own. */
    private suspend fun pageOf(bookId: BookId): BookDetail? {
        val answer = library.book(bookId)
        return (answer as? ApiResult.Success)?.value ?: kept.detail(bookId)?.takeIf { answer.isMissing }
    }

    /** Of the two, the one whose position moved last; the audiobook when neither has a date. */
    private suspend fun lastListened(bookId: BookId, readAloud: FileId): LoadRequest {
        val listened = places.audiobook(bookId)
        val narrated = places.narration(readAloud)
        val narratedAt = listOfNotNull(narrated.heardAt, narrated.marker?.at).maxOrNull()
        val narrationLast = narratedAt != null && (listened.at == null || narratedAt.isAfter(listened.at))
        return if (narrationLast) narration(bookId, readAloud, narrated) else audiobook(bookId, listened)
    }

    private fun audiobook(bookId: BookId, standing: AudiobookStanding): LoadRequest =
        LoadRequest.Audiobook(bookId, fromStart = standing.finished)

    private fun narration(bookId: BookId, fileId: FileId, standing: NarrationStanding): LoadRequest {
        val marker = standing.marker?.takeIf { standing.heardAt == null || it.at.isAfter(standing.heardAt) }
        return LoadRequest.Narration(bookId, fileId, marker?.let(::startAt) ?: NarrationStart.Resume)
    }

    /**
     * The start at a page marker that moved since the narration was heard. A marker that is only a percentage
     * names no place a sentence can be found from: the narration is then taken up where it was
     * ([NarrationStart.Resume]), never at the start of a section.
     */
    private fun startAt(marker: PageMarker): NarrationStart =
        marker.cfi?.let(NarrationStart::AtMarker) ?: NarrationStart.Resume
}

/** [CarPlaces] read from the device's stores and from the server: of each date, the newest. */
class StoredCarPlaces @Inject constructor(
    private val audio: AudioRepository,
    private val playback: PlaybackStore,
    private val reader: ReaderRepository,
    private val narrations: NarrationStore,
    private val positions: PositionStore,
    private val outbox: Outbox,
) : CarPlaces {

    override suspend fun audiobook(bookId: BookId): AudiobookStanding {
        val device = playback.get(bookId)?.at
        val server = (audio.playbackState(bookId) as? ApiResult.Success)?.value
        val serverAt = server?.capturedAt
        // Finished on the server, and this device has no newer place that the server has yet to hear of.
        val listenedSince = device != null && (serverAt == null || device.isAfter(serverAt))
        return AudiobookStanding(
            at = listOfNotNull(device, serverAt).maxOrNull(),
            finished = server?.completed == true && !listenedSince,
        )
    }

    override suspend fun narration(fileId: FileId): NarrationStanding {
        val server = (reader.position(fileId) as? ApiResult.Success)?.value
        val heardAt = listOfNotNull(narrations.get(fileId)?.at, server?.narrationUpdatedAt).maxOrNull()
        val own = ownPosition(fileId, positions, outbox).latest?.let { PageMarker(it.cfi, it.at) }
        val served = server?.updatedAt?.let { PageMarker(server.cfi, it) }
        return NarrationStanding(heardAt, listOfNotNull(own, served).maxByOrNull { it.at })
    }
}
