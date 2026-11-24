package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.downloads.engine.narrationFile
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.PositionStore
import app.tfdn.lirelle.core.reader.sync.ownPosition
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

/** Where the device keeps the positions it knows, and those it still has to send. */
data class DevicePlaces @Inject constructor(
    val positions: PositionStore,
    val outbox: Outbox,
    val narrations: NarrationStore,
    val playback: PlaybackStore,
    val playbackOutbox: PlaybackOutbox,
)

/**
 * Keeps, with a download, where the server says the user is in the book: a book read or listened to on another
 * device then opens at the right place without the network, as it would with it.
 *
 * The positions are the server's own: they are put where the device keeps its last known ones, and nothing is
 * queued to be sent. A position of this device that is as recent or newer, kept or still to send, stays.
 */
class KeptPositions @Inject constructor(
    private val registry: DownloadRegistry,
    private val reader: ReaderRepository,
    private val audio: AudioRepository,
    private val kept: KeptBookData,
    private val places: DevicePlaces,
) {
    /**
     * Asks the server where the user is in [detail], a book the device keeps or is fetching, and keeps it.
     * Nothing for another book. It never fails: what cannot be asked or kept is left as it was.
     */
    suspend fun refresh(detail: BookDetail) {
        try {
            if (registry.book(detail.id) == null) return
            // The files the readers open: the one read, by its text or by its pages, and the one narrated when
            // that is another.
            listOfNotNull(detail.readerFile, detail.pagedFile, detail.narrationFile).distinctBy { it.id }
                .forEach { text(detail.id, it) }
            if (detail.hasAudioFiles) listening(detail.id)
        } catch (_: IOException) {
            // A store that cannot be read or written: the book opens where the device knew.
        }
    }

    /** As a reader does when it follows the server's position at opening: where in the text, or which page. */
    private suspend fun text(bookId: BookId, file: BookFile) {
        val server = (reader.position(file.id) as? ApiResult.Success)?.value ?: return
        val at = server.updatedAt
        val own = ownPosition(file.id, places.positions, places.outbox).latest
        if (at != null && (own == null || own.at.isBefore(at))) {
            places.positions.put(LocalPosition(file.id, server.cfi, server.percent, at, server.page))
        }
        if (file.hasReadAlong) server.narration?.let { narration(bookId, file.id, it, server) }
    }

    /** A place without a date is only better than none. */
    private suspend fun narration(bookId: BookId, fileId: FileId, place: NarrationPlace, server: ReadingPosition) {
        val queued = places.outbox.pending().narrations.lastOrNull { it.fileId == fileId }
        val own = listOfNotNull(places.narrations.get(fileId), queued).maxByOrNull { it.at }
        val at = server.narrationUpdatedAt
        if (own != null && (at == null || !own.at.isBefore(at))) return
        // How far the narration is, by its own clock; the file's progress when its map is not kept.
        val percent = kept.map(bookId, fileId)?.let { it.percentAt(it.timeOfClock(place.seconds)) } ?: server.percent
        places.narrations.put(LocalNarration(fileId, place, percent, at ?: Instant.EPOCH))
    }

    /**
     * What loading the book with the server leaves behind: its place on the timeline of the kept manifest, the
     * one whose files are on the device, and its version as the one the next writes are made over. A place in a
     * file that manifest does not have is not kept.
     */
    private suspend fun listening(bookId: BookId) {
        val manifest = kept.manifest(bookId) ?: return
        val server = (audio.playbackState(bookId) as? ApiResult.Success)?.value ?: return
        val bookTime = BookTimeline(manifest).bookTimeOf(server.assetId, server.positionMs) ?: return
        val queued = places.playbackOutbox.pending().lastOrNull { it.bookId == bookId }
        val own = listOfNotNull(places.playback.get(bookId), queued).maxByOrNull { it.at }
        val at = server.capturedAt
        if (own != null && (at == null || !own.at.isBefore(at))) return
        places.playback.put(
            LocalPlayback(
                bookId = bookId,
                assetId = server.assetId,
                positionMs = server.positionMs,
                bookTimeMs = bookTime,
                at = at ?: Instant.EPOCH,
                baseVersion = server.version,
                contentVersion = manifest.contentVersion,
            ),
        )
        places.playback.setKnownVersion(bookId, server.version)
    }
}
