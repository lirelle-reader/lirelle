package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.audio.narration.toManifest
import app.tfdn.lirelle.core.audio.narration.trackOf
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope

/**
 * Reads what playing the narration of a read-along file needs: the book's page (its titles, its own cover, the
 * file's version), the file's map, where to start and the speed.
 *
 * [NarrationStart.Resume] starts at the newer of this device's narration place and the server's (the start of
 * its sentence, or its time on the narration clock when the sentence is not known); without either, at the
 * beginning. [NarrationStart.AtMarker] starts at the sentence the page marker is in, and as a resumption when
 * the marker names none. The speed is the narration's own for the book, else the book's audiobook speed, else 1×.
 *
 * A narration the device has whole plays the map its download kept, with the server there too: that map is the
 * one of the files on the device, and the one the reader follows. The page kept stands in for the server's when
 * the server cannot give its own, and the cover kept on the device is the one shown: it needs no network.
 */
class NarrationLoader @Inject constructor(
    private val maps: ReadAlongMapStore,
    private val library: LibraryRepository,
    private val reader: ReaderRepository,
    private val narrations: NarrationStore,
    private val settings: PlayerSettingsStore,
    private val readAlong: ReadAlongRepository,
    private val kept: KeptBooks,
) {
    /** Null when the book has no read-along file [fileId] or the map cannot be had. */
    suspend fun load(bookId: BookId, fileId: FileId, start: NarrationStart): LoadedBook? = coroutineScope {
        // The server's place is asked at once: it answers in its own time.
        val serverAsked = if (start !is NarrationStart.AtSentence) async { serverPlace(fileId) } else null
        val copy = kept.copyOf(bookId, DownloadFormat.Narration)
        val page = pageOf(bookId, copy)
        val map = page?.let { mapOf(bookId, fileId, it, copy) }
        val cover = copy?.coverAddress(bookId) ?: page?.coverUrl
        val playlist = if (page != null && map != null) playlistOf(fileId, page, map, cover) else null
        if (map == null || playlist == null) return@coroutineScope null.also { coroutineContext.cancelChildren() }
        val startMs = startOf(start, map) { newer(narrations.get(fileId), serverAsked?.await())?.let(map::timeOf) }
        val speed = settings.narrationSpeedOf(bookId) ?: settings.speedOf(bookId) ?: 1f
        LoadedBook(bookId, playlist, seriesId = null, startMs, speed, BookSource.Narration(fileId, map), copy != null)
    }

    /**
     * Where [start] is on the narration; [resumed] is where it was left, null when it never played. A page
     * marker that names no sentence leaves the narration where it was: never at the start of the marker's
     * section, which may be pages before it.
     */
    private suspend fun startOf(start: NarrationStart, map: ReadAlongMap, resumed: suspend () -> Long?): Long =
        when (start) {
            is NarrationStart.AtSentence -> map.timeOf(start)
            is NarrationStart.AtMarker -> map.sentenceAtMarker(start.cfi)?.startMs ?: resumed()
            NarrationStart.Resume -> resumed()
        } ?: 0

    /** The server's page of the book; the one [copy] kept when the server cannot give its own. */
    private suspend fun pageOf(bookId: BookId, copy: KeptBookData?): BookDetail? {
        val answer = library.book(bookId)
        return (answer as? ApiResult.Success)?.value ?: copy?.takeIf { answer.isMissing }?.detail(bookId)
    }

    /** The map [copy] kept of the read-along file [fileId] of [page], else the device's for the file's version. */
    private suspend fun mapOf(bookId: BookId, fileId: FileId, page: BookDetail, copy: KeptBookData?): ReadAlongMap? {
        val file = page.files.firstOrNull { it.id == fileId && it.kind == BookFileKind.ReadAlongEpub } ?: return null
        return copy?.map(bookId, fileId) ?: maps.map(bookId, fileId, file.contentVersion)
    }

    private fun playlistOf(fileId: FileId, page: BookDetail, map: ReadAlongMap, coverUrl: String?): BookPlaylist? {
        val authors = page.authors.map { it.name }
        val manifest = map.toManifest(page.title, authors, page.narrators, hasCover = page.coverUrl != null)
        return BookPlaylist.ofSegments(
            manifest = manifest,
            coverUrl = coverUrl,
            clip = { asset -> map.trackOf(asset.assetId)?.let { ClipRange(it.clipStartMs, it.clipEndMs) } },
        ) { assetId -> map.trackOf(assetId)?.let { readAlong.trackUrl(page.id, fileId, it.href) } }
    }

    private suspend fun serverPlace(fileId: FileId): DatedPlace? {
        val position = (reader.position(fileId) as? ApiResult.Success)?.value ?: return null
        return position.narration?.let { DatedPlace(it, position.narrationUpdatedAt) }
    }
}

/** What the session needs to play narrations: their loader, and the cursor it tells their end. */
class NarrationParts(val loader: NarrationLoader, val cursor: NarrationCursor)

private data class DatedPlace(val place: NarrationPlace, val at: Instant?)

/** The newer of the device's place and the server's; the device's when the server's has no date. */
private fun newer(device: LocalNarration?, server: DatedPlace?): NarrationPlace? {
    val serverAt = server?.at
    val deviceWins = device != null && (serverAt == null || !device.at.isBefore(serverAt))
    return if (deviceWins) device?.place else server?.place
}

/** The start of [place]'s sentence; its time on the narration clock when the sentence is not in the map. */
private fun ReadAlongMap.timeOf(place: NarrationPlace): Long {
    val fragment = place.fragment.substringAfter('#', missingDelimiterValue = "")
    return sentenceOf(place.sectionIndex, fragment)?.startMs ?: timeOfClock(place.seconds)
}
