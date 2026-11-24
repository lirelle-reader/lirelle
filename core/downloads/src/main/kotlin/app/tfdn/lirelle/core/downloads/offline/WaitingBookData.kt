package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a newer version of a kept format needs besides its files, until those take the place of the kept ones:
 * the audiobook's manifest, the narration's map, and the book's page when it names other files than the kept
 * ones. The kept copy opens with what [KeptBookData] holds, which describes the files on the device: nothing
 * here replaces it. Each waits among the files of its format's update: taken back with them, and put in place
 * with them ([takeWaitingData]).
 *
 * @param maps where the copy of a read-along map comes from.
 */
internal class WaitingBookData(
    private val files: DownloadFiles,
    private val maps: ReadAlongMapStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** False when it cannot be written. */
    suspend fun keepManifest(manifest: AudioManifest): Boolean = withContext(io) {
        files.waiting(manifest.bookId, DownloadFormat.Audiobook, ManifestName)
            .writeJson(files.folders, StoredManifest.serializer(), manifest.stored())
    }

    /** False when the map cannot be had or written. */
    suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean =
        maps.copyTo(bookId, fileId, version, files.waiting(bookId, DownloadFormat.Narration, MapName))

    /** The page that goes with the newer version of [format]; one that cannot be written is only not kept. */
    suspend fun keepPage(detail: BookDetail, format: DownloadFormat) {
        withContext(io) {
            files.waiting(detail.id, format, DetailName)
                .writeJson(files.folders, StoredPage.serializer(), StoredPage(detail.stored()))
        }
    }
}

private fun DownloadFiles.waiting(bookId: BookId, format: DownloadFormat, name: String): File =
    updates.file(bookId, format, name)

/**
 * Puts what waits with the update of [format] in the place of what is kept, each in one step: for who moves the
 * files of that update in, in the same step. What is not there was moved before, or never waited. The page
 * keeps the reading the kept one had.
 *
 * @throws java.io.IOException when a file cannot be moved: it still waits.
 */
internal fun DownloadFiles.takeWaitingData(bookId: BookId, format: DownloadFormat) {
    val own = when (format) {
        DownloadFormat.Text -> null
        DownloadFormat.Audiobook -> ManifestName
        DownloadFormat.Narration -> MapName
    }
    val data = own?.let { waiting(bookId, format, it) }
    if (data != null && data.isFile) {
        Files.move(data.toPath(), kept(bookId, data.name).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    val page = waiting(bookId, format, DetailName)
    val newer = page.readJson(StoredPage.serializer())
    if (newer != null) {
        val before = kept(bookId, DetailName).readJson(StoredPage.serializer())
        val merged = newer.copy(readingPercent = before?.readingPercent, readAt = before?.readAt)
        kept(bookId, DetailName).writeJson(folders, StoredPage.serializer(), merged)
    }
    page.deleteKept()
}
