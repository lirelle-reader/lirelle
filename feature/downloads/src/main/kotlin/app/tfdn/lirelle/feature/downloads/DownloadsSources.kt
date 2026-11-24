package app.tfdn.lirelle.feature.downloads

import android.content.Context
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.LocalProgress
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.ids.BookId
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File

/**
 * Where the screen takes what the registry does not say.
 *
 * @property kept the books' pages and covers.
 * @property percentOf how far the user is in a book, as the device knows it; null for a book never started.
 * @property storage the device's free space and size, now.
 */
class DownloadsSources(
    val kept: KeptBookData,
    val percentOf: suspend (DownloadedBook) -> Int?,
    val storage: () -> DeviceStorage,
)

/**
 * Where the image loader finds a kept cover. The time the file was written is part of it: an update puts
 * another image in the same file, and the loader would otherwise show the one it remembers.
 */
internal fun coverAddress(file: File): String =
    "file://${file.absolutePath}#${file.lastModified()}"

internal suspend fun DownloadsSources.keptOf(book: DownloadedBook): KeptBook {
    val detail = kept.detail(book.bookId)
    return KeptBook(
        title = detail?.title.orEmpty(),
        authors = detail?.authors.orEmpty().joinToString(", ") { it.name },
        cover = kept.coverFile(book.bookId)?.let(::coverAddress),
        coverShape = detail?.coverShape ?: KeptBook.NoPage.coverShape,
        percent = percentOf(book),
    )
}

/**
 * The pages and the progress of the books, read again only when the books or what is complete of them change,
 * or when [of] is given another `epoch`: not at each step of a download.
 */
internal class KeptBooks(private val sources: DownloadsSources) {
    private var key: Any? = null
    private var value: Map<BookId, KeptBook> = emptyMap()

    suspend fun of(books: List<DownloadedBook>, epoch: Int): Map<BookId, KeptBook> {
        val asked = books.map { Triple(it.bookId, it.lastActivity, it.complete()) } to epoch
        if (asked != key) {
            value = books.associate { it.bookId to sources.keptOf(it) }
            key = asked
        }
        return value
    }
}

@Module
@InstallIn(ViewModelComponent::class)
internal object DownloadsModule {

    /** The size is that of the volume the downloads are on: the app's own storage. */
    @Provides
    fun sources(
        kept: KeptBookData,
        progress: LocalProgress,
        files: DownloadFiles,
        @ApplicationContext context: Context,
    ): DownloadsSources = DownloadsSources(kept, progress::percentOf) {
        DeviceStorage(freeBytes = files.freeBytes(), totalBytes = context.filesDir.totalSpace)
    }
}
