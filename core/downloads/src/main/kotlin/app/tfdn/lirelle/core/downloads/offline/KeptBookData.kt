package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.audio.narration.readAlongMapFrom
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.store.namesKeptFiles
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal const val DetailName = "detail.json"
private const val CoverName = "cover"
private const val AudioCoverName = "audio-cover"
internal const val ManifestName = "manifest.json"
internal const val MapName = "map.gz"

/** Where the server said the user was in the book, the last time the page was loaded with a network. */
data class KeptReading(val percent: Int, val readAt: Instant?)

/** What a downloaded book needs without the server. */
interface KeptBookData {
    /**
     * Keeps the book's page (`detail.json`), its cover image, and the audiobook's own cover when the book has
     * one. A cover that cannot be fetched is only not replaced. Without [readingPercent] and [readAt], the reading
     * kept before stays. The files of a page that names those the device keeps whole are not replaced by those of
     * one that does not.
     */
    suspend fun keep(detail: BookDetail, readingPercent: Int? = null, readAt: Instant? = null)

    suspend fun detail(bookId: BookId): BookDetail?

    /** Null when no percent was ever given with the detail. */
    suspend fun reading(bookId: BookId): KeptReading?

    /** The kept cover image, whatever its format; null when there is none. */
    fun coverFile(bookId: BookId): File?

    /**
     * The kept image of the audiobook's own cover, for a book that has one beside its cover; null when there is
     * none.
     */
    fun audioCoverFile(bookId: BookId): File? = null

    suspend fun keepManifest(manifest: AudioManifest)

    suspend fun manifest(bookId: BookId): AudioManifest?

    /**
     * Keeps the download's own copy of the read-along map, which the device's maps, few and the least used out,
     * cannot drop. False when the map cannot be had or written.
     */
    suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean

    suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap?

    /**
     * Removes what only [format] needs: the audiobook's manifest, the narration's map. The page and its covers go
     * with the book: null removes everything kept of it.
     */
    suspend fun remove(bookId: BookId, format: DownloadFormat?)
}

/**
 * In the book's directory of the downloads, beside its formats.
 *
 * @param client the authenticated one: it fetches the cover.
 * @param maps where the copy of a read-along map comes from.
 * @param registry what the device keeps whole of each book: the files a kept page must go on naming.
 */
class FileKeptBookData(
    private val files: DownloadFiles,
    private val client: OkHttpClient,
    private val maps: ReadAlongMapStore,
    private val registry: DownloadRegistry,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : KeptBookData {

    /** One writer at a time: the reading of a detail kept again is taken from the one before. */
    private val mutex = Mutex()

    /**
     * A page that does not name the files the device keeps whole takes the files of the kept page that does, and
     * is kept with the rest of what it says: without the server, the kept copy opens with the files of the kept
     * page. The server may give the book without its file for a moment, for as long as Komga analyses a new one,
     * or name a newer file whose update is not applied yet; that file comes with its update.
     */
    override suspend fun keep(detail: BookDetail, readingPercent: Int?, readAt: Instant?) = mutex.withLock {
        val given = readingPercent != null || readAt != null
        val before = withContext(io) { files.page(detail.id) }
        val keptFiles = before?.detail?.detail()
            ?.takeIf { !registry.namesKeptFiles(detail) && registry.namesKeptFiles(it) }
        val written = keptFiles
            ?.let { detail.copy(files = it.files, readerFileWithoutDownload = it.readerFileWithoutDownload) }
            ?: detail
        val page = StoredPage(
            detail = written.stored(),
            readingPercent = if (given) readingPercent else before?.readingPercent,
            readAt = if (given) readAt else before?.readAt,
        )
        withContext(io) {
            files.kept(detail.id, DetailName).writeJson(files.folders, StoredPage.serializer(), page)
            client.keepImage(detail.coverUrl, files.kept(detail.id, CoverName), files.folders)
            client.keepImage(detail.audioCover?.url, files.kept(detail.id, AudioCoverName), files.folders)
        }
        // What is written once the profile closed is stopped where its folders are made (DownloadFolders).
        Unit
    }

    override suspend fun detail(bookId: BookId): BookDetail? = withContext(io) { files.page(bookId) }?.detail?.detail()

    override suspend fun reading(bookId: BookId): KeptReading? =
        withContext(io) { files.page(bookId) }
            ?.let { page -> page.readingPercent?.let { KeptReading(it, page.readAt) } }

    override fun coverFile(bookId: BookId): File? = files.kept(bookId, CoverName).takeIf { it.isFile }

    override fun audioCoverFile(bookId: BookId): File? = files.kept(bookId, AudioCoverName).takeIf { it.isFile }

    override suspend fun keepManifest(manifest: AudioManifest) = mutex.withLock {
        withContext(io) {
            files.kept(manifest.bookId, ManifestName)
                .writeJson(files.folders, StoredManifest.serializer(), manifest.stored())
        }
        Unit
    }

    override suspend fun manifest(bookId: BookId): AudioManifest? =
        withContext(io) { files.kept(bookId, ManifestName).readJson(StoredManifest.serializer()) }?.manifest()

    override suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean =
        mutex.withLock { maps.copyTo(bookId, fileId, version, files.kept(bookId, MapName)) }

    override suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap? =
        withContext(io) { readAlongMapFrom(files.kept(bookId, MapName), bookId, fileId) }

    override suspend fun remove(bookId: BookId, format: DownloadFormat?) = mutex.withLock {
        val names = when (format) {
            DownloadFormat.Text -> emptyList()
            DownloadFormat.Audiobook -> listOf(ManifestName)
            DownloadFormat.Narration -> listOf(MapName)
            null -> listOf(DetailName, CoverName, AudioCoverName, ManifestName, MapName)
        }
        withContext(io) { names.forEach { files.kept(bookId, it).deleteKept() } }
    }
}

private fun DownloadFiles.page(bookId: BookId): StoredPage? = kept(bookId, DetailName).readJson(StoredPage.serializer())
