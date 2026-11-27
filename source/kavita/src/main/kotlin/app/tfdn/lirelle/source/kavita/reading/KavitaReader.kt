package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.model.FinishedReading
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.kavita.library.KavitaFileRef
import app.tfdn.lirelle.source.kavita.library.KavitaLibraryApi
import app.tfdn.lirelle.source.kavita.library.NotFound
import app.tfdn.lirelle.source.kavita.library.formatOf
import app.tfdn.lirelle.source.kavita.library.kavitaFile
import app.tfdn.lirelle.source.kavita.library.utcInstant
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

/**
 * A Kavita profile's reading: the place of a chapter, written and read with Kavita's progress, and its files.
 *
 * A place is written as Kavita's page: a comic's or a PDF's page less one, an EPUB's spine item with its CFI, the end
 * (100 %) as the page count, which Kavita takes as the chapter read. Kavita makes its reading sessions from these
 * writes: the app's own are accepted and dropped. No narration, finished reading nor rating: Kavita rates series,
 * not books.
 *
 * @param now when the device says it is: the time of a place written, as Kavita dates it on taking it.
 */
internal class KavitaReader(
    session: ProfileSession,
    reading: KavitaApis<KavitaReadingApi>,
    library: KavitaApis<KavitaLibraryApi>,
    private val errors: KavitaErrors,
    now: () -> Instant = Instant::now,
) : ReaderRepository {

    private val server = KavitaReadingServer(session, reading, library)
    private val places = KavitaPlaces(server, now)
    private val downloads = KavitaDownload(server, errors)

    override suspend fun position(fileId: FileId): ApiResult<ReadingPosition> {
        val ref = fileId.kavitaFile() ?: return NotFound
        return errors.call(KavitaCall.Authenticated) {
            coroutineScope {
                val progress = async { server.api().progress(ref.chapterId) }
                val chapter = async { server.freshChapter(ref) }
                val held = progress.await()
                val read = chapter.await()
                positionOf(fileId, held, read.pages, read.epub)
            }
        }
    }

    override suspend fun savePosition(
        fileId: FileId,
        cfi: String?,
        percent: Double,
        editedAt: Instant?,
        page: Int?,
    ): ApiResult<SavedPosition> {
        val ref = fileId.kavitaFile() ?: return NotFound
        return errors.call(KavitaCall.Authenticated) {
            places.save(placeOf(ref, server.chapter(ref), cfi, percent, page), editedAt)
        }
    }

    override suspend fun saveNarration(
        fileId: FileId,
        place: NarrationPlace,
        percent: Double,
        editedAt: Instant?,
    ): ApiResult<SavedPosition> = NotFound

    override suspend fun saveSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> =
        ApiResult.Success(Unit)

    override suspend fun finishedReading(bookId: BookId): ApiResult<FinishedReading?> = ApiResult.Success(null)

    override suspend fun rate(readingId: ReadId, rating: Int?): ApiResult<Unit> = NotFound

    override suspend fun nextVolume(seriesId: SeriesId, bookId: BookId, group: VolumeGroup): ApiResult<NextVolume?> =
        ApiResult.Success(null)

    /** None: a file kept on the device comes with reading offline. */
    override fun fileUrl(fileId: FileId, audioless: Boolean): String? = null

    override suspend fun download(
        fileId: FileId,
        audioless: Boolean,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiResult<Unit> {
        val ref = fileId.kavitaFile() ?: return NotFound
        return downloads.fetch(ref, sink, onProgress)
    }
}

/**
 * What the reader needs of a chapter: its page count (spine items for an [epub]), and the app's name of its first
 * file's [format].
 */
internal class ChapterFacts(val pages: Int, val format: String?) {
    val epub: Boolean get() = format == "EPUB"
}

/**
 * The profile's own server, read at each call, and what it said of the chapters read: asked again when a chapter is
 * opened, so that a place written while it is read (each page turned) asks nothing more than the write.
 */
internal class KavitaReadingServer(
    private val session: ProfileSession,
    private val reading: KavitaApis<KavitaReadingApi>,
    private val library: KavitaApis<KavitaLibraryApi>,
) {
    private val url: String get() = session.session.value.serverUrl
    private val chapters = ConcurrentHashMap<Int, ChapterFacts>()

    fun api(): KavitaReadingApi = reading.forServer(url)

    /** The chapter of a file as Kavita says it now, kept for the places written next. */
    suspend fun freshChapter(ref: KavitaFileRef): ChapterFacts {
        val chapter = library.forServer(url).chapter(ref.chapterId)
        val format = chapter.files.firstOrNull()?.let(::formatOf)
        return ChapterFacts(chapter.pages, format).also { chapters[ref.chapterId] = it }
    }

    /** The chapter of a file as kept, asked only when it is not. */
    suspend fun chapter(ref: KavitaFileRef): ChapterFacts = chapters[ref.chapterId] ?: freshChapter(ref)
}

/**
 * The places written to Kavita. Kavita keeps the last place written and never refuses one, whatever its date: a
 * place sent late (the outbox's, `editedAt` set) first reads Kavita's, and is set aside when Kavita holds one dated
 * after it — a place moved on another device meanwhile. Kavita holding the very same place counts as saved, at
 * Kavita's date. The place the user is at right now is written at once. Kavita dates a place when it takes it, and
 * its answer tells no date: a place written is stored at the device's [now] once Kavita answered, the device's
 * estimate of Kavita's date, late or not.
 */
private class KavitaPlaces(private val server: KavitaReadingServer, private val now: () -> Instant) {

    suspend fun save(place: ProgressWriteDto, editedAt: Instant?): SavedPosition =
        heldOver(place, editedAt) ?: write(place)

    /**
     * What a place sent late becomes without being written: saved when Kavita holds the very same place, set aside
     * when Kavita holds one dated after [editedAt]. Null when it is to be written: a live place ([editedAt] null),
     * a chapter never opened, or one Kavita holds an older place of.
     */
    private suspend fun heldOver(place: ProgressWriteDto, editedAt: Instant?): SavedPosition? {
        if (editedAt == null) return null
        val held = server.api().progress(place.chapterId)
        val heldAt = utcInstant(held.lastModifiedUtc) ?: return null
        return when {
            held.pageNum == place.pageNum && held.bookScrollId == place.bookScrollId ->
                SavedPosition(SaveOutcome.Saved, heldAt)
            heldAt.isAfter(editedAt) -> SavedPosition(SaveOutcome.Superseded, heldAt)
            else -> null
        }
    }

    private suspend fun write(place: ProgressWriteDto): SavedPosition {
        val answer = server.api().saveProgress(place)
        if (!answer.isSuccessful) throw HttpException(answer)
        return SavedPosition(SaveOutcome.Saved, now())
    }
}

/**
 * The place to write of the file of [ref] in its [chapter]: its page from 0, or for an EPUB its spine item and its
 * [cfi]. A [page] missing for a file read by pages is a caller's mistake, written as the first page.
 */
private fun placeOf(ref: KavitaFileRef, chapter: ChapterFacts, cfi: String?, percent: Double, page: Int?) =
    ProgressWriteDto(
        volumeId = ref.volumeId,
        chapterId = ref.chapterId,
        pageNum = if (chapter.epub) {
            spineNumOf(cfi, percent, chapter.pages)
        } else {
            pageNumOf(page ?: 1, percent, chapter.pages)
        },
        seriesId = ref.seriesId,
        libraryId = ref.libraryId,
        bookScrollId = if (chapter.epub) cfi else null,
    )
