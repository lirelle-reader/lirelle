package app.tfdn.lirelle.source.komga.reading

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
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.komga.comics.KomgaPageFormats
import app.tfdn.lirelle.source.komga.comics.pageAddress
import app.tfdn.lirelle.source.komga.library.filesUnavailable
import app.tfdn.lirelle.source.komga.library.titleOf
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaCall
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.dto.BookDto
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

private const val Percent = 100.0
private const val Conflict = 409
private const val Absent = 404

/** What this installation's devices are called on Komga, before the profile's id: `lirelle-3f2a9c1d`. */
private const val DevicePrefix = "lirelle-"

/** The format of a page whose format Komga did not say. */
private const val DefaultPageType = "image/jpeg"

private val NotFound = ApiResult.Failure(ApiError.NotFound)

/**
 * A Komga profile's reading of its comics (a Komga book is one file, whose id is the book's).
 *
 * The place is the book's read progress: its page, and when it was read — the time Komga compares a place written
 * later with. A place is written as a Readium progression of this device (its id, its name), timed when the page
 * was reached; Komga sets it aside (409) when it holds one read later. A place sent late is then
 * [SaveOutcome.Superseded]; the place the user is at right now is sent again just after Komga's, once, as the
 * reading contract asks of a live place (and fails, for the outbox to try again, when Komga cannot say what it
 * holds). A place Komga holds from this very device at the same page is one sent before whose answer was lost, or
 * which Komga dated otherwise: it is saved. The end of the book is sent as its last page, which Komga takes as the book
 * read. A place of a book whose pages Komga serves none of for now (its media not ready) fails for the outbox to try
 * again later; one of a book gone is not found.
 *
 * No narration, reading session, finished reading, rating nor whole file (Komga's capabilities; files are 3c).
 *
 * @param pageFormats the formats of a book's pages, kept by the page source: their number is the book's.
 * @param now when the device says it is, for the place the user is at right now.
 */
internal class KomgaReader(
    session: ProfileSession,
    api: KomgaApis<KomgaReadingApi>,
    private val errors: KomgaErrors,
    private val pageFormats: KomgaPageFormats,
    deviceLabel: DeviceLabelProvider,
    now: () -> Instant = Instant::now,
) : ReaderRepository {

    private val server = KomgaReadingServer(session, api)
    private val progressions = KomgaProgressions(server, DevicePrefix + session.profileId, deviceLabel, now)

    override suspend fun position(fileId: FileId): ApiResult<ReadingPosition> = errors.call(KomgaCall.Content) {
        positionOf(fileId, server.api().book(fileId.value))
    }

    override suspend fun savePosition(
        fileId: FileId,
        cfi: String?,
        percent: Double,
        editedAt: Instant?,
        page: Int?,
    ): ApiResult<SavedPosition> {
        // Komga keeps the page of a comic: a place without one is of no file it serves.
        if (page == null) return NotFound
        return errors.call(KomgaCall.Content) {
            val formats = formatsOf(fileId)
            // The end, even shown as a last spread of two whose first page the reader gives, is the last page:
            // Komga then marks the book read. Komga refuses a page outside the book.
            val number = if (percent >= Percent) formats.size else page.coerceIn(1, formats.size)
            progressions.save(PagePlace(fileId, number, formats), editedAt)
        }
    }

    /**
     * The formats of the book's pages. When Komga lists none (`/pages` answers 404 while the book's media is not
     * ready), the book says why: one whose file Komga analyses again, or could not analyse, is still there and its
     * place is worth sending once Komga serves it again, possibly days later — it fails as a server that cannot take
     * it for now ([ApiError.ServerFailed]), which the outbox tries again for as long as it takes. A book gone (404),
     * or ready with no page, is not found, and its place is given up.
     */
    private suspend fun formatsOf(fileId: FileId): List<String> {
        val formats = try {
            pageFormats.formats(fileId)
        } catch (refused: HttpException) {
            if (refused.code() != Absent) throw refused
            emptyList()
        }
        if (formats.isNotEmpty()) return formats
        val book = server.api().book(fileId.value)
        throw ApiErrorException(if (filesUnavailable(book)) ApiError.ServerFailed else ApiError.NotFound)
    }

    override suspend fun saveNarration(
        fileId: FileId,
        place: NarrationPlace,
        percent: Double,
        editedAt: Instant?,
    ): ApiResult<SavedPosition> = NotFound

    override suspend fun saveSession(fileId: FileId, session: ReadingSessionReport): ApiResult<Unit> = NotFound

    override suspend fun finishedReading(bookId: BookId): ApiResult<FinishedReading?> = ApiResult.Success(null)

    override suspend fun rate(readingId: ReadId, rating: Int?): ApiResult<Unit> = NotFound

    /** The series' next book on Komga, whatever its kind; none after the last. */
    override suspend fun nextVolume(seriesId: SeriesId, bookId: BookId, group: VolumeGroup): ApiResult<NextVolume?> =
        errors.call(KomgaCall.Content) {
            val answer = server.api().next(bookId.value)
            when {
                answer.code() == Absent -> null
                !answer.isSuccessful -> throw HttpException(answer)
                else -> answer.body()?.let(::nextVolumeOf)
            }
        }

    override fun fileUrl(fileId: FileId, audioless: Boolean): String? = null

    override suspend fun download(
        fileId: FileId,
        audioless: Boolean,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiResult<Unit> = NotFound
}

/** The profile's own server, read at each call. */
private class KomgaReadingServer(private val session: ProfileSession, private val api: KomgaApis<KomgaReadingApi>) {
    val url: String get() = session.session.value.serverUrl

    fun api(): KomgaReadingApi = api.forServer(url)
}

/** The page numbered [number] (from 1) of the book of [fileId], whose pages have these [formats]. */
private class PagePlace(val fileId: FileId, val number: Int, val formats: List<String>)

/** What Komga holds of a place: when it was read, on which device, at which page (from 1). */
private class StoredPlace(val at: Instant, val deviceId: String?, val page: Int?)

/** The progressions this device writes to Komga. */
private class KomgaProgressions(
    private val server: KomgaReadingServer,
    private val deviceId: String,
    private val deviceLabel: DeviceLabelProvider,
    private val now: () -> Instant,
) {

    /** The place, timed when its page was reached ([editedAt]), or now for the place the user is at. */
    suspend fun save(place: PagePlace, editedAt: Instant?): SavedPosition =
        write(place, millis(editedAt ?: now()), live = editedAt == null)

    /**
     * Sends the place; when Komga sets it aside, what it holds decides. A [live] place must not be lost: when Komga
     * cannot say what it holds, the save fails, for the outbox to try again. A late one is simply set aside.
     */
    private suspend fun write(place: PagePlace, modified: Instant, live: Boolean): SavedPosition {
        val answer = server.api().saveProgression(place.fileId.value, progression(place, modified))
        if (answer.isSuccessful) return SavedPosition(SaveOutcome.Saved, modified)
        if (answer.code() != Conflict) throw HttpException(answer)
        val stored = if (live) {
            stored(place.fileId) ?: throw ApiErrorException(ApiError.Unexpected)
        } else {
            storedOrNull(place.fileId) ?: return SavedPosition(SaveOutcome.Superseded, storedAt = null)
        }
        return when {
            stored.isOurs(place) -> SavedPosition(SaveOutcome.Saved, stored.at)
            // Once only: a place set aside again is somebody else's, newer still.
            live -> write(place, maxOf(millis(now()), stored.at.plusMillis(1)), live = false)
            else -> SavedPosition(SaveOutcome.Superseded, stored.at)
        }
    }

    /** This very device's place at the same page: one sent before, whatever time Komga gives it. */
    private fun StoredPlace.isOurs(place: PagePlace) =
        deviceId == this@KomgaProgressions.deviceId && page == place.number

    /** What Komga holds now; null when it holds nothing, or says it in a way the app cannot read. */
    private suspend fun stored(fileId: FileId): StoredPlace? {
        val answer = server.api().progression(fileId.value)
        if (!answer.isSuccessful) throw HttpException(answer)
        val dto = answer.body() ?: return null
        val at = instantOf(dto.modified) ?: return null
        return StoredPlace(at, dto.device?.id, dto.locator?.locations?.position)
    }

    /** What Komga holds now; null when it holds nothing, or cannot say. */
    private suspend fun storedOrNull(fileId: FileId): StoredPlace? = try {
        stored(fileId)
    } catch (_: IOException) {
        null
    } catch (_: HttpException) {
        null
    } catch (_: SerializationException) {
        null
    }

    private fun progression(place: PagePlace, modified: Instant) = ProgressionDto(
        modified = modified.toString(),
        device = DeviceDto(deviceId, deviceLabel.label()),
        locator = LocatorDto(
            href = pageAddress(server.url, place.fileId, place.number).toString(),
            type = place.formats[place.number - 1].substringBefore(';').trim().ifEmpty { DefaultPageType },
            locations = LocationsDto(
                fragments = emptyList(),
                position = place.number,
                totalProgression = place.number.toDouble() / place.formats.size,
            ),
        ),
    )
}

/** Komga is sent no finer time than the millisecond. */
private fun millis(at: Instant): Instant = at.truncatedTo(ChronoUnit.MILLIS)

/**
 * The place of a book: none when never opened; the last page of a book marked read, whatever page Komga kept; the
 * time it was read (the time Komga compares a later place with), else when Komga last wrote it.
 */
private fun positionOf(fileId: FileId, dto: BookDto): ReadingPosition {
    val progress = dto.readProgress ?: return ReadingPosition(fileId, cfi = null, percent = 0.0, updatedAt = null)
    val count = dto.media.pagesCount
    val page = if (progress.completed && count > 0) count else progress.page
    val percent = when {
        progress.completed -> Percent
        count <= 0 -> 0.0
        else -> (page * Percent / count).coerceIn(0.0, Percent)
    }
    return ReadingPosition(
        fileId = fileId,
        cfi = null,
        percent = percent,
        updatedAt = instantOf(progress.readDate) ?: instantOf(progress.lastModified),
        page = page.takeIf { it > 0 },
    )
}

private fun nextVolumeOf(dto: BookDto) =
    NextVolume(BookId(dto.id), titleOf(dto), dto.metadata.number?.trim()?.takeIf { it.isNotEmpty() })

/** A time Komga sends, in UTC (`Z`) or with its offset; null when absent or unreadable. */
private fun instantOf(text: String?): Instant? = text?.let {
    try {
        OffsetDateTime.parse(it).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}
