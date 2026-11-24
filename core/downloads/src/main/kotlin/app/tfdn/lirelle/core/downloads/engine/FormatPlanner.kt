package app.tfdn.lirelle.core.downloads.engine

import androidx.annotation.OptIn
import app.tfdn.lirelle.core.ids.BookId
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.offline.KeptPositions
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject

/**
 * The files of one format of a book.
 *
 * @property fileId the file of the text, for the text and the narration; null for an audiobook.
 * @property version what the server calls the format's content: the file's content version, or the audiobook
 *   manifest's content version.
 * @property textName what the text is named by, from its file's format; of no use to the other formats.
 * @property expectedBytes about what the files take together, when the server says no size for each of them:
 *   for a text made of pages, the size of the file they are drawn from. Null for a plan whose files say
 *   their own.
 */
data class FormatPlan(
    val fileId: FileId?,
    val version: String?,
    val files: List<PlannedFile>,
    val textName: TextName = TextName.Text,
    val expectedBytes: Long? = null,
)

/**
 * What a plan is asked for, which says where what it learns besides the files goes: a [Download] keeps it with
 * the book; a [Check] and an [Update] are of a format the device has whole, and leave what that copy opens with
 * as it is, the update putting the newer beside it until its files take the place of the kept ones.
 */
enum class PlanFor { Download, Check, Update }

/** Says what a format of a book is made of. */
fun interface FormatPlans {
    suspend fun plan(bookId: BookId, format: DownloadFormat, purpose: PlanFor): ApiResult<FormatPlan>
}

/** The plan of a download. */
suspend fun FormatPlans.plan(bookId: BookId, format: DownloadFormat): ApiResult<FormatPlan> =
    plan(bookId, format, PlanFor.Download)

/**
 * What each format of a book is made of, asked to the server. Each file says what it is, not where it is
 * served: that is asked when it is fetched, of the `DownloadLocations`.
 */
@ProfileScoped
class FormatPlanner @Inject internal constructor(
    private val library: LibraryRepository,
    private val audio: AudioRepository,
    private val readAlong: ReadAlongRepository,
    private val maps: ReadAlongMapStore,
    private val data: PlannedData,
    private val positions: KeptPositions,
    private val paged: PagedPlans,
) : FormatPlans {
    /**
     * The files of [format] for the book as it is on the server now; also stores what the format needs kept: the
     * book's page and cover, the audiobook's manifest, the narration's map, and where the server says the user
     * is in the book. A plan that fails keeps nothing. For a format the device already has whole ([purpose]),
     * what its kept copy opens with is not replaced: see [PlannedData].
     *
     * A book without this format is [ApiError.NotFound]; one merged into another is [ApiError.Moved], and is not
     * planned under the number asked. A check or an update of a book whose files the server cannot serve for now
     * ([BookDetail.filesUnavailable]) is [ApiError.Unexpected]: no answer about the book, an update to retry.
     */
    override suspend fun plan(bookId: BookId, format: DownloadFormat, purpose: PlanFor): ApiResult<FormatPlan> {
        val detail = when (val book = library.book(bookId)) {
            is ApiResult.Failure -> return book
            is ApiResult.Success -> book.value
        }
        if (detail.id != bookId) return ApiResult.Failure(ApiError.Moved(detail.id))
        // The server holds the book's files but serves none of them for now (Komga analyses a new file): a check
        // gets no answer, the book neither gone nor updated, and an update fails as one to retry, the kept copy
        // still there. A download fails for want of the file, as before.
        if (detail.filesUnavailable && purpose != PlanFor.Download) return ApiResult.Failure(ApiError.Unexpected)
        val plan = when (format) {
            DownloadFormat.Text -> text(detail, purpose)
            DownloadFormat.Audiobook -> audiobook(detail, purpose)
            DownloadFormat.Narration -> narration(detail, purpose)
        }
        if (plan is ApiResult.Success) {
            data.page(detail, format, purpose)
            // After the manifest and the map: the positions are placed on the kept ones.
            positions.refresh(detail)
        }
        return plan
    }

    private suspend fun text(detail: BookDetail, purpose: PlanFor): ApiResult<FormatPlan> {
        val file = detail.textFile ?: return ApiResult.Failure(ApiError.NotFound)
        // A book read page by page from the server, a comic or a DjVu, has no single file to keep: its pages are.
        val served = servedKind(file)
        if (served != null) return paged.plan(detail.id, file, served, purpose)
        val plan = FormatPlan(file.id, file.contentVersion, listOf(whole(file)), TextName.of(file.format))
        return ApiResult.Success(plan)
    }

    /**
     * The file of the text as its reader would fetch it: the file itself, or its version without audio for a
     * read-along file. It keeps its own extension, and is checked as what it is.
     */
    private fun whole(file: BookFile): PlannedFile = PlannedFile(
        name = textName(file),
        source = if (file.hasReadAlong) FileSource.Audioless(file.id) else FileSource.Whole(file.id),
        version = file.contentVersion,
        // What the server builds without the audio has no size known beforehand, and answers no range.
        expectedBytes = file.sizeBytes.takeUnless { file.hasReadAlong },
        resumable = !file.hasReadAlong,
        kind = textKind(file),
    )

    @OptIn(UnstableApi::class)
    private suspend fun audiobook(detail: BookDetail, purpose: PlanFor): ApiResult<FormatPlan> {
        val manifest = when (val answer = audio.manifest(detail.id)) {
            is ApiResult.Failure -> return answer
            is ApiResult.Success -> answer.value
        }
        val files = manifest.assets.sortedBy { it.sequence }.map { asset ->
            val name = assetName(asset.sequence, asset.assetId)
            // Under the key the player caches it: a file already listened to whole is not fetched again.
            val key = AudioCache.key(asset.assetId, asset.etag)
            val source = FileSource.AudioAsset(asset.assetId)
            PlannedFile(name, source, asset.etag, asset.sizeBytes, resumable = true, FileKind.Audio, cacheKey = key)
        }
        // The files of an update without their manifest would not play once in the place of the kept ones.
        if (!data.manifest(manifest, purpose)) return ApiResult.Failure(ApiError.Unexpected)
        return ApiResult.Success(FormatPlan(fileId = null, version = manifest.contentVersion, files = files))
    }

    private suspend fun narration(detail: BookDetail, purpose: PlanFor): ApiResult<FormatPlan> {
        val file = detail.narrationFile ?: return ApiResult.Failure(ApiError.NotFound)
        val map = maps.map(detail.id, file.id, file.contentVersion)
            ?: return ApiResult.Failure(whyNoMap(detail.id, file.id))
        val files = narrationFiles(file, map)
        // A map that cannot be kept is a narration that would not play without the server.
        return if (data.map(detail.id, file.id, file.contentVersion, purpose)) {
            ApiResult.Success(FormatPlan(file.id, file.contentVersion, files))
        } else {
            ApiResult.Failure(ApiError.Unexpected)
        }
    }

    /** The EPUB, then the map's audio files in reading order. */
    private fun narrationFiles(file: BookFile, map: ReadAlongMap): List<PlannedFile> {
        val version = "${file.id}:${file.contentVersion}"
        // The same file may be read in several places: it is fetched once.
        val tracks = map.tracks.distinctBy { it.href }.mapIndexed { position, track ->
            val source = FileSource.ReadAlongTrack(file.id, track.href)
            PlannedFile(trackName(position, track.href), source, version, track.sizeBytes, true, FileKind.Audio)
        }
        return listOf(whole(file)) + tracks
    }

    /** The store only says that it has no map: the server is asked again for its reason. */
    private suspend fun whyNoMap(bookId: BookId, fileId: FileId): ApiError =
        (readAlong.playlist(bookId, fileId) as? ApiResult.Failure)?.error
            ?: (readAlong.spine(bookId, fileId) as? ApiResult.Failure)?.error
            ?: ApiError.Unexpected
}
